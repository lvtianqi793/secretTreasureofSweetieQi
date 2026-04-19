package com.energy.management.controller;

import com.energy.management.dto.ApiResponse;
import com.energy.management.dto.EnergyQueryRequest;
import com.energy.management.dto.PageResult;
import com.energy.management.service.EnergyQueryService;
import com.energy.management.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能耗数据查询接口
 * 支持按建筑、时间、监测参数等多条件精准查询
 */
@Tag(name = "能耗数据查询", description = "多条件精准查询能耗数据")
@RestController
@RequestMapping("/energy")
@RequiredArgsConstructor
public class EnergyQueryController {

    private final EnergyQueryService energyQueryService;
    private final ReportService reportService;

    /** 导出最大行数上限 (保护后端内存) */
    private static final int EXPORT_MAX_ROWS = 100_000;

    /**
     * 多条件分页查询
     */
    @Operation(summary = "多条件查询", description = "按建筑、时间、能源类型、数值范围等条件查询")
    @PostMapping("/query")
    public ApiResponse<PageResult<Map<String, Object>>> query(@RequestBody EnergyQueryRequest request) {
        if (request.getEnergyType() == null || request.getEnergyType().isBlank()) {
            return ApiResponse.error(400, "请指定能源类型 (electricity/water/gas/steam/chilledwater/hotwater/solar/irrigation)");
        }
        PageResult<Map<String, Object>> result = energyQueryService.query(request);
        return ApiResponse.success(result);
    }

    /**
     * 导出查询数据 (支持 xlsx / csv)
     * format=xlsx 返回 Excel, format=csv 返回 UTF-8 BOM 的 CSV
     */
    @Operation(summary = "导出查询数据",
            description = "按当前筛选条件导出原始记录, format=xlsx|csv, 最多 10万 行")
    @PostMapping("/query/export")
    public ResponseEntity<byte[]> exportQuery(
            @RequestBody EnergyQueryRequest request,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) Integer maxRows) throws IOException {

        if (request.getEnergyType() == null || request.getEnergyType().isBlank()) {
            throw new IllegalArgumentException(
                    "请指定能源类型 (electricity/water/gas/steam/chilledwater/hotwater/solar/irrigation)");
        }

        int cap = (maxRows == null || maxRows <= 0) ? EXPORT_MAX_ROWS : Math.min(maxRows, EXPORT_MAX_ROWS);
        long total = energyQueryService.countByConditions(request);
        List<Map<String, Object>> records = energyQueryService.queryAll(request, cap);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String baseName = "能耗数据_" + request.getEnergyType() + "_" + timestamp;

        if ("csv".equalsIgnoreCase(format)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            reportService.writeQueryDataCsv(baos, records);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=" + URLEncoder.encode(baseName + ".csv", StandardCharsets.UTF_8))
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(baos.toByteArray());
        }

        byte[] bytes = reportService.generateQueryDataReport(records, request.getEnergyType(), total);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + URLEncoder.encode(baseName + ".xlsx", StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /**
     * 获取建筑列表
     */
    @Operation(summary = "获取建筑列表")
    @GetMapping("/buildings")
    public ApiResponse<List<Map<String, Object>>> getBuildingList() {
        return ApiResponse.success(energyQueryService.getBuildingList());
    }

    /**
     * 获取建筑类型列表
     */
    @Operation(summary = "获取建筑类型")
    @GetMapping("/building-types")
    public ApiResponse<List<String>> getBuildingTypes() {
        return ApiResponse.success(energyQueryService.getBuildingTypes());
    }

    /**
     * 获取数据时间范围
     */
    @Operation(summary = "获取数据时间范围")
    @GetMapping("/time-range/{energyType}")
    public ApiResponse<Map<String, Object>> getTimeRange(@PathVariable String energyType) {
        return ApiResponse.success(energyQueryService.getTimeRange(energyType));
    }

    /**
     * 获取支持的能源类型列表
     */
    @Operation(summary = "获取支持的能源类型")
    @GetMapping("/types")
    public ApiResponse<List<String>> getEnergyTypes() {
        return ApiResponse.success(List.of(
                "electricity", "water", "gas", "steam",
                "chilledwater", "hotwater", "solar", "irrigation"
        ));
    }

    /**
     * 下拉框选项: 能源类型 (含中文标签/单位) + 建筑类型
     * 前端一次拉取即可填充两个 select
     */
    @Operation(summary = "获取下拉框选项 (能源类型 + 建筑类型)",
            description = "前端用于一次性填充能源类型/建筑类型下拉框")
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> getOptions() {
        List<Map<String, String>> energyTypes = List.of(
                energyOption("electricity",  "电力能耗", "kWh"),
                energyOption("water",        "用水量",   "m³"),
                energyOption("gas",          "天然气",   "therms"),
                energyOption("steam",        "蒸汽",     "lbs"),
                energyOption("chilledwater", "冷冻水",   "ton-hours"),
                energyOption("hotwater",     "热水",     "kBtu"),
                energyOption("solar",        "光伏发电", "kWh"),
                energyOption("irrigation",   "灌溉用水", "gallon")
        );

        List<String> buildingTypes = energyQueryService.getBuildingTypes();
        List<Map<String, Object>> buildings = energyQueryService.getBuildingList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("energyTypes", energyTypes);
        result.put("buildingTypes", buildingTypes);
        result.put("buildings", buildings);
        return ApiResponse.success(result);
    }

    private static Map<String, String> energyOption(String value, String label, String unit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        m.put("unit", unit);
        return m;
    }
}
