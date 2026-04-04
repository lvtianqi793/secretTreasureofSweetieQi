package com.energy.management.controller;

import com.energy.management.config.AiConfig;
import com.energy.management.dto.ApiResponse;
import com.energy.management.service.DataImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统信息与健康检查
 */
@Tag(name = "系统管理", description = "系统状态与健康检查")
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
public class SystemController {

    private final DataImportService dataImportService;
    private final AiConfig aiConfig;

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "running");
        info.put("timestamp", LocalDateTime.now().toString());
        info.put("aiModel", aiConfig.getModel());
        info.put("aiBaseUrl", aiConfig.getBaseUrl());
        return ApiResponse.success(info);
    }

    @Operation(summary = "系统概览", description = "获取数据库各表数据量与系统状态")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("systemName", "建筑能源智能管理系统");
        overview.put("version", "1.0.0");
        overview.put("timestamp", LocalDateTime.now().toString());
        overview.put("aiConfig", Map.of(
                "model", aiConfig.getModel(),
                "baseUrl", aiConfig.getBaseUrl()
        ));
        overview.put("dataOverview", dataImportService.getDataOverview());
        return ApiResponse.success(overview);
    }
}
