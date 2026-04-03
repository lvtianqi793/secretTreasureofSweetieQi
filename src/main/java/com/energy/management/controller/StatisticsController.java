package com.energy.management.controller;

import com.energy.management.dto.ApiResponse;
import com.energy.management.dto.StatisticsRequest;
import com.energy.management.dto.StatisticsResult;
import com.energy.management.service.ReportService;
import com.energy.management.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统计分析与报表接口
 * 实现3类核心统计: 时段汇总、COP计算、数据异常分析
 */
@Tag(name = "统计分析", description = "能耗统计分析与报表导出")
@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final ReportService reportService;

    /**
     * 执行统计分析
     */
    @Operation(summary = "统计分析", description = "支持summary(时段汇总)、cop(COP计算)、anomaly(异常分析)")
    @PostMapping("/analyze")
    public ApiResponse<StatisticsResult> analyze(@RequestBody StatisticsRequest request) {
        validateRequest(request);
        StatisticsResult result = statisticsService.analyze(request);
        return ApiResponse.success(result);
    }

    /**
     * 时段汇总 (快捷接口)
     */
    @Operation(summary = "时段汇总")
    @GetMapping("/summary")
    public ApiResponse<StatisticsResult> summary(
            @RequestParam String energyType,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "day") String granularity) {

        StatisticsRequest request = new StatisticsRequest();
        request.setEnergyType(energyType);
        request.setBuildingId(buildingId);
        request.setStartTime(parseTime(startTime));
        request.setEndTime(parseTime(endTime));
        request.setGranularity(granularity);
        request.setAnalysisType("summary");

        return ApiResponse.success(statisticsService.analyze(request));
    }

    /**
     * COP分析 (快捷接口)
     */
    @Operation(summary = "COP分析")
    @GetMapping("/cop")
    public ApiResponse<StatisticsResult> cop(
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "month") String granularity) {

        StatisticsRequest request = new StatisticsRequest();
        request.setEnergyType("chilledwater"); // COP需要冷冻水+电力
        request.setBuildingId(buildingId);
        request.setStartTime(parseTime(startTime));
        request.setEndTime(parseTime(endTime));
        request.setGranularity(granularity);
        request.setAnalysisType("cop");

        return ApiResponse.success(statisticsService.analyze(request));
    }

    /**
     * 异常分析 (快捷接口)
     */
    @Operation(summary = "异常检测")
    @GetMapping("/anomaly")
    public ApiResponse<StatisticsResult> anomaly(
            @RequestParam String energyType,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        StatisticsRequest request = new StatisticsRequest();
        request.setEnergyType(energyType);
        request.setBuildingId(buildingId);
        request.setStartTime(parseTime(startTime));
        request.setEndTime(parseTime(endTime));
        request.setAnalysisType("anomaly");

        return ApiResponse.success(statisticsService.analyze(request));
    }

    /**
     * 导出统计报表 (Excel)
     */
    @Operation(summary = "导出Excel报表")
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportReport(@RequestBody StatisticsRequest request) throws IOException {
        validateRequest(request);
        byte[] excelBytes = reportService.generateStatisticsReport(request);

        String fileName = "能耗统计报表_" + request.getAnalysisType() + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + ".xlsx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(excelBytes);
    }

    private void validateRequest(StatisticsRequest request) {
        if (request.getAnalysisType() == null) {
            throw new IllegalArgumentException("请指定分析类型: summary/cop/anomaly");
        }
        if (!"cop".equals(request.getAnalysisType()) &&
            (request.getEnergyType() == null || request.getEnergyType().isBlank())) {
            throw new IllegalArgumentException("请指定能源类型");
        }
    }

    private LocalDateTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        try {
            return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(timeStr + " 00:00:00",
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                throw new IllegalArgumentException("时间格式错误, 请使用: yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
            }
        }
    }
}
