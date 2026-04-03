package com.energy.management.controller;

import com.energy.management.dto.ApiResponse;
import com.energy.management.dto.ImportResult;
import com.energy.management.service.DataImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据导入接口
 * 支持CSV文件上传导入和本地目录批量导入
 */
@Tag(name = "数据导入", description = "CSV能耗数据导入管理")
@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
public class DataImportController {

    private final DataImportService dataImportService;

    /**
     * 上传CSV文件导入
     */
    @Operation(summary = "上传CSV导入", description = "通过文件上传方式导入CSV能耗数据")
    @PostMapping("/import/upload")
    public ApiResponse<ImportResult> uploadCsv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "请选择要上传的CSV文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".csv")) {
            return ApiResponse.error(400, "仅支持CSV文件格式");
        }
        try {
            ImportResult result = dataImportService.importCsvFile(file);
            return ApiResponse.success("导入完成", result);
        } catch (IOException e) {
            return ApiResponse.error("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 批量上传多个CSV文件
     */
    @Operation(summary = "批量上传CSV", description = "同时上传多个CSV文件导入")
    @PostMapping("/import/batch-upload")
    public ApiResponse<List<ImportResult>> batchUploadCsv(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ApiResponse.error(400, "请选择要上传的CSV文件");
        }
        List<ImportResult> results = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            try {
                results.add(dataImportService.importCsvFile(file));
            } catch (Exception e) {
                results.add(ImportResult.builder()
                        .fileName(file.getOriginalFilename())
                        .status("failed")
                        .message(e.getMessage())
                        .build());
            }
        }
        return ApiResponse.success("批量导入完成", results);
    }

    /**
     * 从服务器本地路径导入
     */
    @Operation(summary = "本地路径导入", description = "从服务器本地CSV文件路径导入")
    @PostMapping("/import/path")
    public ApiResponse<ImportResult> importFromPath(@RequestParam String filePath) {
        try {
            ImportResult result = dataImportService.importCsvFromPath(filePath);
            return ApiResponse.success("导入完成", result);
        } catch (IOException e) {
            return ApiResponse.error("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 从服务器本地目录批量导入
     */
    @Operation(summary = "目录批量导入", description = "导入指定目录下的所有CSV文件")
    @PostMapping("/import/directory")
    public ApiResponse<List<ImportResult>> importFromDirectory(@RequestParam String dirPath) {
        try {
            List<ImportResult> results = dataImportService.importDirectory(dirPath);
            return ApiResponse.success("目录导入完成", results);
        } catch (IOException e) {
            return ApiResponse.error("目录读取失败: " + e.getMessage());
        }
    }

    /**
     * 获取数据概况 (各表数据量)
     */
    @Operation(summary = "数据概况", description = "查看各能耗表的数据量")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Long>> getDataOverview() {
        return ApiResponse.success(dataImportService.getDataOverview());
    }

    /**
     * 清空指定表数据
     */
    @Operation(summary = "清空表数据", description = "清空指定能耗表中的所有数据")
    @DeleteMapping("/clear/{tableName}")
    public ApiResponse<String> clearTable(@PathVariable String tableName) {
        dataImportService.clearTable(tableName);
        return ApiResponse.success("已清空表: " + tableName);
    }
}
