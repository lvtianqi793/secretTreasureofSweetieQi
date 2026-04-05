package com.energy.management.repository;

import com.energy.management.entity.HotWaterData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HotWaterDataRepository extends JpaRepository<HotWaterData, Long> {

    @Query("SELECT e FROM HotWaterData e WHERE " +
           "(:buildingId IS NULL OR e.buildingId LIKE %:buildingId%) AND " +
           "(:buildingType IS NULL OR e.buildingType = :buildingType) AND " +
           "(:startTime IS NULL OR e.monitorTime >= :startTime) AND " +
           "(:endTime IS NULL OR e.monitorTime <= :endTime)")
    Page<HotWaterData> queryByConditions(
            @Param("buildingId") String buildingId,
            @Param("buildingType") String buildingType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    List<HotWaterData> findByBuildingIdAndMonitorTimeBetween(
            String buildingId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT DISTINCT e.buildingId FROM HotWaterData e")
    List<String> findAllBuildingIds();
}
