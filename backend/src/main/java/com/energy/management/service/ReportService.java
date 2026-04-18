package com.energy.management.service;

import com.energy.management.dto.ChartData;
import com.energy.management.dto.StatisticsRequest;
import com.energy.management.dto.StatisticsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.MarkerStyle;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFLineChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFPieChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 报表生成与导出服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final StatisticsService statisticsService;

    /**
     * 生成统计报表Excel
     */
    public byte[] generateStatisticsReport(StatisticsRequest request) throws IOException {
        StatisticsResult result = statisticsService.analyze(request);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            // 创建标题样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);

            // ===== 汇总信息Sheet =====
            Sheet summarySheet = workbook.createSheet("汇总信息");
            int rowNum = 0;

            Row titleRow = summarySheet.createRow(rowNum++);
            createCell(titleRow, 0, "建筑能源统计分析报表", headerStyle);

            Row dateRow = summarySheet.createRow(rowNum++);
            createCell(dateRow, 0, "生成时间: " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), dataStyle);

            Row typeRow = summarySheet.createRow(rowNum++);
            createCell(typeRow, 0, "分析类型: " + request.getAnalysisType(), dataStyle);
            createCell(typeRow, 1, "能源类型: " + request.getEnergyType(), dataStyle);

            rowNum++; // 空行

            if (result.getSummary() != null) {
                StatisticsResult.SummaryInfo s = result.getSummary();

                Row hdr = summarySheet.createRow(rowNum++);
                String[] labels = {"指标", "值"};
                for (int i = 0; i < labels.length; i++) {
                    createCell(hdr, i, labels[i], headerStyle);
                }

                String[][] data = {
                        {"总计", String.format("%.2f %s", s.getTotalValue(), s.getUnit())},
                        {"均值", String.format("%.4f %s", s.getAvgValue(), s.getUnit())},
                        {"最大值", String.format("%.2f %s", s.getMaxValue(), s.getUnit())},
                        {"最小值", String.format("%.2f %s", s.getMinValue(), s.getUnit())},
                        {"标准差", String.format("%.4f", s.getStdDev())},
                        {"记录数", String.valueOf(s.getRecordCount())}
                };
                for (String[] d : data) {
                    Row r = summarySheet.createRow(rowNum++);
                    createCell(r, 0, d[0], dataStyle);
                    createCell(r, 1, d[1], dataStyle);
                }
            }

            summarySheet.autoSizeColumn(0);
            summarySheet.autoSizeColumn(1);

            // ===== 时间序列Sheet (含折线图) =====
            if (result.getTimeSeries() != null && !result.getTimeSeries().isEmpty()) {
                XSSFSheet tsSheet = workbook.createSheet("时间序列");
                int tsRow = 0;

                Row tsHeader = tsSheet.createRow(tsRow++);
                createCell(tsHeader, 0, "时间段", headerStyle);
                createCell(tsHeader, 1, "累计值", headerStyle);
                createCell(tsHeader, 2, "记录数", headerStyle);

                // 写入数值列时使用真正的数值(供图表引用)
                for (StatisticsResult.TimeSeriesPoint point : result.getTimeSeries()) {
                    Row r = tsSheet.createRow(tsRow++);
                    createCell(r, 0, point.getTimePeriod(), dataStyle);
                    createNumericCell(r, 1, point.getValue(), dataStyle);
                    createNumericCell(r, 2, point.getCount(), dataStyle);
                }

                tsSheet.autoSizeColumn(0);
                tsSheet.autoSizeColumn(1);
                tsSheet.autoSizeColumn(2);

                // 内嵌折线图: 展示时段趋势
                String unit = result.getSummary() != null ? result.getSummary().getUnit() : "";
                embedLineChart(tsSheet, tsRow - 1, "时段趋势", "时间", unit, "累计值");
            }

            // ===== COP结果Sheet (含柱状图) =====
            if (result.getCopResults() != null && !result.getCopResults().isEmpty()) {
                XSSFSheet copSheet = workbook.createSheet("COP分析");
                int copRow = 0;

                Row copHeader = copSheet.createRow(copRow++);
                String[] copHeaders = {"时间段", "建筑编号", "制冷输出(ton-hours)", "电力输入(kWh)", "COP"};
                for (int i = 0; i < copHeaders.length; i++) {
                    createCell(copHeader, i, copHeaders[i], headerStyle);
                }

                for (StatisticsResult.CopResult cop : result.getCopResults()) {
                    Row r = copSheet.createRow(copRow++);
                    createCell(r, 0, cop.getTimePeriod(), dataStyle);
                    createCell(r, 1, cop.getBuildingId(), dataStyle);
                    createNumericCell(r, 2, cop.getCoolingOutput(), dataStyle);
                    createNumericCell(r, 3, cop.getElectricInput(), dataStyle);
                    createNumericCell(r, 4, cop.getCop(), dataStyle);
                }

                for (int i = 0; i < 5; i++) copSheet.autoSizeColumn(i);

                // 内嵌柱状图: 各建筑 COP 对比
                embedBarChart(copSheet, copRow - 1,
                        "各建筑 COP 对比",
                        "建筑编号", "COP",
                        1,  // 类目列: 建筑编号 (B列)
                        4,  // 数值列: COP (E列)
                        "COP");
            }

            // ===== 异常数据Sheet =====
            if (result.getAnomalies() != null && !result.getAnomalies().isEmpty()) {
                Sheet anomSheet = workbook.createSheet("异常分析");
                int anomRow = 0;

                Row anomHeader = anomSheet.createRow(anomRow++);
                String[] anomHeaders = {"建筑编号", "监测时间", "实际值", "均值", "标准差", "Z-Score", "异常类型"};
                for (int i = 0; i < anomHeaders.length; i++) {
                    createCell(anomHeader, i, anomHeaders[i], headerStyle);
                }

                for (StatisticsResult.AnomalyRecord anom : result.getAnomalies()) {
                    Row r = anomSheet.createRow(anomRow++);
                    createCell(r, 0, anom.getBuildingId(), dataStyle);
                    createCell(r, 1, anom.getMonitorTime(), dataStyle);
                    createCell(r, 2, String.format("%.2f", anom.getValue()), dataStyle);
                    createCell(r, 3, String.format("%.4f", anom.getMean()), dataStyle);
                    createCell(r, 4, String.format("%.4f", anom.getStdDev()), dataStyle);
                    createCell(r, 5, String.format("%.2f", anom.getZScore()), dataStyle);
                    createCell(r, 6, anom.getAnomalyType(), dataStyle);
                }

                for (int i = 0; i < 7; i++) anomSheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    /**
     * 生成单图表报表 Excel (含数据 + 内嵌图表)
     */
    public byte[] generateChartReport(ChartData chart) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);

            String chartType = chart.getChartType() == null ? "line" : chart.getChartType().toLowerCase();
            XSSFSheet sheet = workbook.createSheet("图表数据");

            int lastRow;
            String title = chart.getTitle() != null ? chart.getTitle() : "可视化图表";

            if ("pie".equals(chartType)) {
                // 饼图: 两列 (名称, 数值)
                Row header = sheet.createRow(0);
                createCell(header, 0, "名称", headerStyle);
                createCell(header, 1, "数值" + (chart.getUnit() != null ? " (" + chart.getUnit() + ")" : ""), headerStyle);

                int r = 1;
                if (chart.getPieData() != null) {
                    for (ChartData.PiePoint p : chart.getPieData()) {
                        Row row = sheet.createRow(r++);
                        createCell(row, 0, p.getName(), dataStyle);
                        createNumericCell(row, 1, p.getValue(), dataStyle);
                    }
                }
                lastRow = r - 1;
                sheet.autoSizeColumn(0);
                sheet.autoSizeColumn(1);
                if (lastRow >= 1) embedPieChart(sheet, lastRow, title);
            } else {
                // 折线/柱状: 首列类目 + N 列系列
                List<ChartData.Series> seriesList = chart.getSeries();
                Row header = sheet.createRow(0);
                createCell(header, 0, chart.getXAxisLabel() != null ? chart.getXAxisLabel() : "类目", headerStyle);
                if (seriesList != null) {
                    for (int i = 0; i < seriesList.size(); i++) {
                        createCell(header, i + 1, seriesList.get(i).getName(), headerStyle);
                    }
                }

                int categoryCount = chart.getCategories() == null ? 0 : chart.getCategories().size();
                for (int i = 0; i < categoryCount; i++) {
                    Row row = sheet.createRow(i + 1);
                    createCell(row, 0, chart.getCategories().get(i), dataStyle);
                    if (seriesList != null) {
                        for (int s = 0; s < seriesList.size(); s++) {
                            List<Double> data = seriesList.get(s).getData();
                            if (data != null && i < data.size() && data.get(i) != null) {
                                createNumericCell(row, s + 1, data.get(i), dataStyle);
                            }
                        }
                    }
                }
                lastRow = categoryCount;

                for (int i = 0; i <= (seriesList == null ? 0 : seriesList.size()); i++) {
                    sheet.autoSizeColumn(i);
                }

                String xTitle = chart.getXAxisLabel() != null ? chart.getXAxisLabel() : "";
                String yTitle = chart.getYAxisLabel() != null ? chart.getYAxisLabel() : "";
                if (lastRow >= 1) {
                    if ("bar".equals(chartType)) {
                        embedBarChart(sheet, lastRow, title, xTitle, yTitle,
                                0, 1,
                                seriesList != null && !seriesList.isEmpty()
                                        ? seriesList.get(0).getName() : "数值");
                    } else {
                        embedLineChart(sheet, lastRow, title, xTitle, yTitle,
                                seriesList != null && !seriesList.isEmpty()
                                        ? seriesList.get(0).getName() : "数值");
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    /**
     * 生成原始查询数据 Excel
     */
    public byte[] generateQueryDataReport(List<Map<String, Object>> records,
                                          String energyType,
                                          long total) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);

            Sheet sheet = workbook.createSheet(energyType == null ? "data" : energyType);
            Row header = sheet.createRow(0);
            String[] columns = {"ID", "建筑编号", "建筑类型", "监测时间", "数值"};
            for (int i = 0; i < columns.length; i++) {
                createCell(header, i, columns[i], headerStyle);
            }

            int rowIdx = 1;
            for (Map<String, Object> record : records) {
                Row row = sheet.createRow(rowIdx++);
                createCell(row, 0, toStr(record.get("id")), dataStyle);
                createCell(row, 1, toStr(record.get("buildingId")), dataStyle);
                createCell(row, 2, toStr(record.get("buildingType")), dataStyle);
                createCell(row, 3, toStr(record.get("monitorTime")), dataStyle);
                Object v = record.get("value");
                if (v instanceof Number num) {
                    createNumericCell(row, 4, num.doubleValue(), dataStyle);
                } else {
                    createCell(row, 4, toStr(v), dataStyle);
                }
            }
            for (int i = 0; i < columns.length; i++) sheet.autoSizeColumn(i);

            // 末行附注信息
            Row noteRow = sheet.createRow(rowIdx + 1);
            createCell(noteRow, 0, "共 " + total + " 条, 本次导出 " + records.size() + " 条", null);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    /**
     * 将原始查询数据以 CSV 流式写入 (UTF-8 with BOM, Excel 友好)
     */
    public void writeQueryDataCsv(OutputStream out,
                                  List<Map<String, Object>> records) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            // BOM, 避免 Excel 打开中文乱码
            writer.write('\uFEFF');
            writer.write("id,building_id,building_type,monitor_time,value\n");
            for (Map<String, Object> record : records) {
                writer.write(csvEscape(toStr(record.get("id"))));
                writer.write(',');
                writer.write(csvEscape(toStr(record.get("buildingId"))));
                writer.write(',');
                writer.write(csvEscape(toStr(record.get("buildingType"))));
                writer.write(',');
                writer.write(csvEscape(toStr(record.get("monitorTime"))));
                writer.write(',');
                writer.write(csvEscape(toStr(record.get("value"))));
                writer.write('\n');
            }
            writer.flush();
        }
    }

    private String toStr(Object val) {
        return val == null ? "" : val.toString();
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        if (style != null) cell.setCellStyle(style);
    }

    /** 写入数值单元格 (供图表引用) */
    private void createNumericCell(Row row, int col, double value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    /**
     * 在 Sheet 中内嵌折线图
     *
     * @param sheet       目标 Sheet (已写入数据, 第0行为表头)
     * @param lastDataRow 最后一行数据的行号 (0-based)
     * @param title       图表标题
     * @param xTitle      X轴标题
     * @param yTitle      Y轴单位
     * @param seriesName  数据系列名
     */
    private void embedLineChart(XSSFSheet sheet, int lastDataRow,
                                String title, String xTitle, String yTitle, String seriesName) {
        if (lastDataRow < 1) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        // 图表放在数据右侧 (第4列起, 占 12 列宽 × 20 行高)
        XSSFClientAnchor anchor = drawing.createAnchor(
                0, 0, 0, 0, 4, 0, 16, 20);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle(xTitle);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle(yTitle);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, lastDataRow, 0, 0));
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                sheet, new CellRangeAddress(1, lastDataRow, 1, 1));

        XDDFLineChartData data = (XDDFLineChartData)
                chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);
        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(seriesName, null);
        if (series instanceof XDDFLineChartData.Series lineSeries) {
            lineSeries.setSmooth(false);
            lineSeries.setMarkerStyle(MarkerStyle.CIRCLE);
        }
        chart.plot(data);
    }

    /**
     * 在 Sheet 中内嵌柱状图
     *
     * @param sheet       目标 Sheet
     * @param lastDataRow 最后一行数据 (0-based)
     * @param title       图表标题
     * @param xTitle      X轴标题
     * @param yTitle      Y轴标题
     * @param categoryCol 类目列 (0-based)
     * @param valueCol    数值列 (0-based)
     * @param seriesName  数据系列名
     */
    private void embedBarChart(XSSFSheet sheet, int lastDataRow,
                               String title, String xTitle, String yTitle,
                               int categoryCol, int valueCol, String seriesName) {
        if (lastDataRow < 1) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(
                0, 0, 0, 0, 6, 0, 18, 20);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle(xTitle);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle(yTitle);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, lastDataRow, categoryCol, categoryCol));
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                sheet, new CellRangeAddress(1, lastDataRow, valueCol, valueCol));

        XDDFBarChartData data = (XDDFBarChartData)
                chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        data.setBarDirection(BarDirection.COL);
        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(seriesName, null);
        chart.plot(data);
    }

    /**
     * 在 Sheet 中内嵌饼图 (数据: A列名称 + B列数值)
     */
    private void embedPieChart(XSSFSheet sheet, int lastDataRow, String title) {
        if (lastDataRow < 1) return;

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(
                0, 0, 0, 0, 3, 0, 15, 20);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.RIGHT);

        XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, lastDataRow, 0, 0));
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                sheet, new CellRangeAddress(1, lastDataRow, 1, 1));

        XDDFPieChartData data = (XDDFPieChartData) chart.createData(ChartTypes.PIE, null, null);
        data.setVaryColors(true);
        data.addSeries(categories, values);
        chart.plot(data);
    }
}
