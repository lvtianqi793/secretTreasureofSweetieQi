package com.energy.management.repository;

import com.energy.management.entity.ElectricityData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ElectricityDataRepository extends JpaRepository<ElectricityData, Long> {

    Page<ElectricityData> findByBuildingIdContainingAndMonitorTimeBetween(
            String buildingId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    @Query("SELECT e FROM ElectricityData e WHERE " +
           "(:buildingId IS NULL OR e.buildingId LIKE %:buildingId%) AND " +
           "(:buildingType IS NULL OR e.buildingType = :buildingType) AND " +
           "(:startTime IS NULL OR e.monitorTime >= :startTime) AND " +
           "(:endTime IS NULL OR e.monitorTime <= :endTime) AND " +
           "(:minValue IS NULL OR e.electricityKwh >= :minValue) AND " +
           "(:maxValue IS NULL OR e.electricityKwh <= :maxValue)")
    Page<ElectricityData> queryByConditions(
            @Param("buildingId") String buildingId,
            @Param("buildingType") String buildingType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("minValue") Double minValue,
            @Param("maxValue") Double maxValue,
            Pageable pageable);

    @Query("SELECT e.buildingId, " +
           "SUM(e.electricityKwh) as totalKwh, " +
           "AVG(e.electricityKwh) as avgKwh, " +
           "MAX(e.electricityKwh) as maxKwh, " +
           "MIN(e.electricityKwh) as minKwh, " +
           "COUNT(e) as cnt " +
           "FROM ElectricityData e WHERE " +
           "(:buildingId IS NULL OR e.buildingId LIKE %:buildingId%) AND " +
           "(:startTime IS NULL OR e.monitorTime >= :startTime) AND " +
           "(:endTime IS NULL OR e.monitorTime <= :endTime) " +
           "GROUP BY e.buildingId ORDER BY totalKwh DESC")
    List<Object[]> summaryByBuilding(
            @Param("buildingId") String buildingId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    List<ElectricityData> findByBuildingIdAndMonitorTimeBetween(
            String buildingId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT DISTINCT e.buildingId FROM ElectricityData e")
    List<String> findAllBuildingIds();

    @Query("SELECT DISTINCT e.buildingType FROM ElectricityData e")
    List<String> findAllBuildingTypes();

    long countByBuildingId(String buildingId);
}
