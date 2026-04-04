package com.energy.management.service;

import com.energy.management.dto.StatisticsRequest;
import com.energy.management.dto.StatisticsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

            // ===== 时间序列Sheet =====
            if (result.getTimeSeries() != null && !result.getTimeSeries().isEmpty()) {
                Sheet tsSheet = workbook.createSheet("时间序列");
                int tsRow = 0;

                Row tsHeader = tsSheet.createRow(tsRow++);
                createCell(tsHeader, 0, "时间段", headerStyle);
                createCell(tsHeader, 1, "累计值", headerStyle);
                createCell(tsHeader, 2, "记录数", headerStyle);

                for (StatisticsResult.TimeSeriesPoint point : result.getTimeSeries()) {
                    Row r = tsSheet.createRow(tsRow++);
                    createCell(r, 0, point.getTimePeriod(), dataStyle);
                    createCell(r, 1, String.format("%.2f", point.getValue()), dataStyle);
                    createCell(r, 2, String.valueOf(point.getCount()), dataStyle);
                }

                tsSheet.autoSizeColumn(0);
                tsSheet.autoSizeColumn(1);
                tsSheet.autoSizeColumn(2);
            }

            // ===== COP结果Sheet =====
            if (result.getCopResults() != null && !result.getCopResults().isEmpty()) {
                Sheet copSheet = workbook.createSheet("COP分析");
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
                    createCell(r, 2, String.format("%.2f", cop.getCoolingOutput()), dataStyle);
                    createCell(r, 3, String.format("%.2f", cop.getElectricInput()), dataStyle);
                    createCell(r, 4, String.format("%.4f", cop.getCop()), dataStyle);
                }

                for (int i = 0; i < 5; i++) copSheet.autoSizeColumn(i);
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

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        if (style != null) cell.setCellStyle(style);
    }
}
