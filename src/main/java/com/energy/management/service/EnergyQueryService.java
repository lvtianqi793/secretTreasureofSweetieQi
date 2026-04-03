package com.energy.management.service;

import com.energy.management.dto.EnergyQueryRequest;
import com.energy.management.dto.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 能耗数据查询服务 - 支持多条件精准查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnergyQueryService {

    @PersistenceContext
    private EntityManager entityManager;

    private static final Map<String, String[]> TABLE_MAP = Map.of(
            "electricity", new String[]{"electricity_data", "electricity_kwh"},
            "water",       new String[]{"water_data", "water_m3"},
            "gas",         new String[]{"gas_data", "gas_therms"},
            "steam",       new String[]{"steam_data", "steam_lbs"},
            "chilledwater",new String[]{"chilledwater_data", "chilledwater_tonhours"},
            "hotwater",    new String[]{"hotwater_data", "hotwater_kbtu"},
            "solar",       new String[]{"solar_data", "solar_kwh"},
            "irrigation",  new String[]{"irrigation_data", "irrigation_gallon"}
    );

    /**
     * 多条件分页查询
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> query(EnergyQueryRequest request) {
        String[] tableInfo = TABLE_MAP.get(request.getEnergyType());
        if (tableInfo == null) {
            throw new IllegalArgumentException("不支持的能源类型: " + request.getEnergyType());
        }
        String tableName = tableInfo[0];
        String valueCol = tableInfo[1];

        // 构建WHERE子句
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (request.getBuildingId() != null && !request.getBuildingId().isBlank()) {
            where.append(" AND building_id LIKE :buildingId ");
            params.put("buildingId", "%" + request.getBuildingId() + "%");
        }
        if (request.getBuildingType() != null && !request.getBuildingType().isBlank()) {
            where.append(" AND building_type = :buildingType ");
            params.put("buildingType", request.getBuildingType());
        }
        if (request.getStartTime() != null) {
            where.append(" AND monitor_time >= :startTime ");
            params.put("startTime", request.getStartTime());
        }
        if (request.getEndTime() != null) {
            where.append(" AND monitor_time <= :endTime ");
            params.put("endTime", request.getEndTime());
        }
        if (request.getMinValue() != null) {
            where.append(" AND ").append(valueCol).append(" >= :minValue ");
            params.put("minValue", request.getMinValue());
        }
        if (request.getMaxValue() != null) {
            where.append(" AND ").append(valueCol).append(" <= :maxValue ");
            params.put("maxValue", request.getMaxValue());
        }

        // COUNT查询
        String countSql = "SELECT COUNT(*) FROM " + tableName + where;
        Query countQuery = entityManager.createNativeQuery(countSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        // 排序
        String orderBy;
        String sortBy = request.getSortBy();
        if ("value".equals(sortBy)) {
            orderBy = valueCol;
        } else {
            orderBy = "monitor_time";
        }
        String sortOrder = "asc".equalsIgnoreCase(request.getSortOrder()) ? "ASC" : "DESC";

        // 数据查询
        int page = Math.max(1, request.getPage());
        int pageSize = Math.min(Math.max(1, request.getPageSize()), 500);
        int offset = (page - 1) * pageSize;

        String dataSql = "SELECT id, building_id, building_type, monitor_time, " + valueCol +
                " FROM " + tableName + where +
                " ORDER BY " + orderBy + " " + sortOrder +
                " LIMIT " + pageSize + " OFFSET " + offset;

        Query dataQuery = entityManager.createNativeQuery(dataSql);
        params.forEach(dataQuery::setParameter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();

        List<Map<String, Object>> records = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", row[0]);
            record.put("buildingId", row[1]);
            record.put("buildingType", row[2]);
            record.put("monitorTime", row[3] != null ? row[3].toString() : null);
            record.put("value", row[4]);
            records.add(record);
        }

        return PageResult.of(records, total, page, pageSize);
    }

    /**
     * 获取建筑列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBuildingList() {
        String sql = """
                SELECT DISTINCT building_id, building_type 
                FROM electricity_data 
                ORDER BY building_type, building_id
                """;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("buildingId", row[0]);
            map.put("buildingType", row[1]);
            result.add(map);
        }
        return result;
    }

    /**
     * 获取建筑类型列表
     */
    @Transactional(readOnly = true)
    public List<String> getBuildingTypes() {
        String sql = "SELECT DISTINCT building_type FROM electricity_data ORDER BY building_type";
        @SuppressWarnings("unchecked")
        List<String> types = entityManager.createNativeQuery(sql).getResultList();
        return types;
    }

    /**
     * 获取数据时间范围
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getTimeRange(String energyType) {
        String[] tableInfo = TABLE_MAP.get(energyType);
        if (tableInfo == null) {
            throw new IllegalArgumentException("不支持的能源类型: " + energyType);
        }
        String sql = "SELECT MIN(monitor_time), MAX(monitor_time), COUNT(*) FROM " + tableInfo[0];
        Object[] row = (Object[]) entityManager.createNativeQuery(sql).getSingleResult();
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("minTime", row[0] != null ? row[0].toString() : null);
        range.put("maxTime", row[1] != null ? row[1].toString() : null);
        range.put("totalRecords", row[2]);
        return range;
    }
}
