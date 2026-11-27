package com.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.entity.WmsInventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 库存表 Mapper
 */
public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {

    /**
     * 原子累加库存：唯一键 uk_wlm(warehouse_id, location_id, material_id) 不存在则插入，存在则原地累加。
     * 单条 SQL 完成，避免 read-modify-write 在并发下丢更新。
     */
    @Insert("INSERT INTO wms_inventory (warehouse_id, location_id, material_id, quantity, frozen_qty) "
            + "VALUES (#{warehouseId}, #{locationId}, #{materialId}, #{delta}, 0) "
            + "ON DUPLICATE KEY UPDATE quantity = quantity + #{delta}")
    int addStock(@Param("warehouseId") Long warehouseId,
                 @Param("locationId") Long locationId,
                 @Param("materialId") Long materialId,
                 @Param("delta") int delta);
}
