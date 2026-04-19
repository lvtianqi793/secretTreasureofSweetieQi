package com.energy.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CSV数据导入结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportResult {
    private String fileName;
    private String tableName;
    private long totalRows;
    private long importedRows;
    private long skippedRows;
    private long timeCostMs;
    private String status; // success, partial, failed
    private String message;
}
