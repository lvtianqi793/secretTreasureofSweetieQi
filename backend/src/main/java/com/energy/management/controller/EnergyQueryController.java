package com.energy.management.controller;

import com.energy.management.dto.ApiResponse;
import com.energy.management.dto.EnergyQueryRequest;
import com.energy.management.dto.PageResult;
import com.energy.management.service.EnergyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
