package com.energy.management.service;

import com.energy.management.dto.ImportResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * CSV数据导入服务
 * 支持批量导入9种能耗CSV数据文件到PostgreSQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataImportService {

    @PersistenceContext
    private EntityManager entityManager;

    private static final int BATCH_SIZE = 5000;

    /** CSV文件名关键词 -> [表名, 值列名] */
    private static final Map<String, String[]> FILE_TABLE_MAP = new LinkedHashMap<>();
    static {
        FILE_TABLE_MAP.put("electricity", new String[]{"electricity_data", "electricity_kwh"});
        FILE_TABLE_MAP.put("water",       new String[]{"water_data", "water_m3"});
        FILE_TABLE_MAP.put("gas",         new String[]{"gas_data", "gas_therms"});
        FILE_TABLE_MAP.put("steam",       new String[]{"steam_data", "steam_lbs"});
        FILE_TABLE_MAP.put("chilledwater",new String[]{"chilledwater_data", "chilledwater_tonhours"});
        FILE_TABLE_MAP.put("hotwater",    new String[]{"hotwater_data", "hotwater_kbtu"});
        FILE_TABLE_MAP.put("solar",       new String[]{"solar_data", "solar_kwh"});
        FILE_TABLE_MAP.put("irrigation",  new String[]{"irrigation_data", "irrigation_gallon"});
    }

    /** 天气数据单独处理 (双值列) */
    private static final String WEATHER_KEY = "weather";

    private static final DateTimeFormatter[] TIME_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    };

    /**
     * 通过文件上传导入CSV
     */
    @Transactional
    public ImportResult importCsvFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("开始导入CSV文件: {}", fileName);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return doImport(reader, fileName);
        }
    }

    /**
     * 从本地文件路径导入CSV
     */
    @Transactional
    public ImportResult importCsvFromPath(String filePath) throws IOException {
        File file = new File(filePath);
        String fileName = file.getName();
        log.info("开始从路径导入CSV: {}", filePath);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return doImport(reader, fileName);
        }
    }

    /**
     * 批量导入目录下所有CSV文件
     */
    @Transactional
    public List<ImportResult> importDirectory(String dirPath) throws IOException {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("路径不是目录: " + dirPath);
        }

        File[] csvFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            throw new FileNotFoundException("目录中没有CSV文件: " + dirPath);
        }

        List<ImportResult> results = new ArrayList<>();
        Arrays.sort(csvFiles, Comparator.comparing(File::getName));
        for (File csvFile : csvFiles) {
            try {
                results.add(importCsvFromPath(csvFile.getAbsolutePath()));
            } catch (Exception e) {
                log.error("导入文件失败: {}", csvFile.getName(), e);
                results.add(ImportResult.builder()
                        .fileName(csvFile.getName())
                        .status("failed")
                        .message(e.getMessage())
                        .build());
            }
        }
        return results;
    }

    /**
     * 核心导入逻辑
     */
    private ImportResult doImport(BufferedReader reader, String fileName) throws IOException {
        long startTime = System.currentTimeMillis();
        String lowerName = fileName.toLowerCase();

        // 判断文件对应的表
        boolean isWeather = lowerName.contains(WEATHER_KEY);
        String[] tableInfo = null;
        String energyType = null;

        if (!isWeather) {
            for (Map.Entry<String, String[]> entry : FILE_TABLE_MAP.entrySet()) {
                if (lowerName.contains(entry.getKey())) {
                    tableInfo = entry.getValue();
                    energyType = entry.getKey();
                    break;
                }
            }
            if (tableInfo == null) {
                return ImportResult.builder()
                        .fileName(fileName).status("failed")
                        .message("无法识别文件类型, 文件名应包含: " + FILE_TABLE_MAP.keySet())
                        .build();
            }
        }

        // 读取表头
        String header = reader.readLine();
        if (header == null) {
            return ImportResult.builder()
                    .fileName(fileName).status("failed").message("文件为空").build();
        }

        // 解析并批量插入
        String line;
        long totalRows = 0;
        long importedRows = 0;
        long skippedRows = 0;
        List<String> batchValues = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            totalRows++;
            try {
                String[] cols = parseCsvLine(line);
                if (cols.length < 4) { skippedRows++; continue; }

                String buildingId = cols[0].trim();
                String buildingType = cols[1].trim();
                LocalDateTime monitorTime = parseDateTime(cols[2].trim());
                if (monitorTime == null) { skippedRows++; continue; }

                if (isWeather) {
                    double temp = parseDoubleOrZero(cols[3]);
                    double humidity = cols.length > 4 ? parseDoubleOrZero(cols[4]) : 0;
                    batchValues.add(String.format(
                            "('%s','%s','%s',%f,%f)",
                            escapeSql(buildingId), escapeSql(buildingType),
                            monitorTime.toString(), temp, humidity
                    ));
                } else {
                    double value = parseDoubleOrZero(cols[3]);
                    batchValues.add(String.format(
                            "('%s','%s','%s',%f)",
                            escapeSql(buildingId), escapeSql(buildingType),
                            monitorTime.toString(), value
                    ));
                }
                importedRows++;

                // 批量执行
                if (batchValues.size() >= BATCH_SIZE) {
                    flushBatch(batchValues, isWeather ? "weather_data" : tableInfo[0],
                            isWeather, isWeather ? null : tableInfo[1]);
                    batchValues.clear();
                    entityManager.flush();
                    entityManager.clear();
                }

            } catch (Exception e) {
                skippedRows++;
                if (skippedRows <= 5) {
                    log.warn("跳过第{}行: {} - {}", totalRows, line, e.getMessage());
                }
            }
        }

        // 刷入剩余数据
        if (!batchValues.isEmpty()) {
            flushBatch(batchValues, isWeather ? "weather_data" : tableInfo[0],
                    isWeather, isWeather ? null : tableInfo[1]);
        }

        long costMs = System.currentTimeMillis() - startTime;
        log.info("导入完成: {} -> {} 行 (跳过 {}), 耗时 {}ms",
                fileName, importedRows, skippedRows, costMs);

        return ImportResult.builder()
                .fileName(fileName)
                .tableName(isWeather ? "weather_data" : tableInfo[0])
                .totalRows(totalRows)
                .importedRows(importedRows)
                .skippedRows(skippedRows)
                .timeCostMs(costMs)
                .status(skippedRows == 0 ? "success" : "partial")
                .message("导入成功")
                .build();
    }

    /**
     * 批量INSERT
     */
    private void flushBatch(List<String> values, String tableName,
                            boolean isWeather, String valueCol) {
        if (values.isEmpty()) return;

        String columns;
        if (isWeather) {
            columns = "(building_id, building_type, monitor_time, temperature_f, humidity_pct)";
        } else {
            columns = "(building_id, building_type, monitor_time, " + valueCol + ")";
        }

        String sql = "INSERT INTO " + tableName + " " + columns + " VALUES "
                + String.join(",", values);

        entityManager.createNativeQuery(sql).executeUpdate();
    }

    /** 简单CSV行解析 (处理引号) */
    private String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result.toArray(new String[0]);
    }

    /** 解析时间 (尝试多种格式) */
    private LocalDateTime parseDateTime(String str) {
        for (DateTimeFormatter fmt : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(str, fmt);
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private double parseDoubleOrZero(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private String escapeSql(String s) {
        return s.replace("'", "''");
    }

    /**
     * 获取各表的数据量概况
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getDataOverview() {
        Map<String, Long> overview = new LinkedHashMap<>();
        String[] tables = {"electricity_data", "water_data", "gas_data", "steam_data",
                "chilledwater_data", "hotwater_data", "solar_data", "irrigation_data", "weather_data"};
        for (String table : tables) {
            try {
                Object count = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table)
                        .getSingleResult();
                overview.put(table, ((Number) count).longValue());
            } catch (Exception e) {
                overview.put(table, -1L); // 表可能不存在
            }
        }
        return overview;
    }

    /**
     * 清空指定表数据
     */
    @Transactional
    public void clearTable(String tableName) {
        // 安全检查
        List<String> allowedTables = List.of("electricity_data", "water_data", "gas_data",
                "steam_data", "chilledwater_data", "hotwater_data", "solar_data",
                "irrigation_data", "weather_data");
        if (!allowedTables.contains(tableName)) {
            throw new IllegalArgumentException("不允许操作的表: " + tableName);
        }
        entityManager.createNativeQuery("TRUNCATE TABLE " + tableName).executeUpdate();
        log.info("已清空表: {}", tableName);
    }
}
