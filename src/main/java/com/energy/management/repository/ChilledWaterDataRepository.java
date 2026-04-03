package com.energy.management.repository;

import com.energy.management.entity.ChilledWaterData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChilledWaterDataRepository extends JpaRepository<ChilledWaterData, Long> {

    @Query("SELECT e FROM ChilledWaterData e WHERE " +
           "(:buildingId IS NULL OR e.buildingId LIKE %:buildingId%) AND " +
           "(:buildingType IS NULL OR e.buildingType = :buildingType) AND " +
           "(:startTime IS NULL OR e.monitorTime >= :startTime) AND " +
           "(:endTime IS NULL OR e.monitorTime <= :endTime)")
    Page<ChilledWaterData> queryByConditions(
            @Param("buildingId") String buildingId,
            @Param("buildingType") String buildingType,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    List<ChilledWaterData> findByBuildingIdAndMonitorTimeBetween(
            String buildingId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT DISTINCT e.buildingId FROM ChilledWaterData e")
    List<String> findAllBuildingIds();
}
