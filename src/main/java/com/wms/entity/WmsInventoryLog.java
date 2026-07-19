package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存流水表（wms_inventory_log）
 */
@Data
@TableName("wms_inventory_log")
public class WmsInventoryLog implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 物料ID */
    private Long materialId;

    /** 仓库ID */
    private Long warehouseId;

    /** 库位ID */
    private Long locationId;

    /** 变动类型：入库/出库/移库/盘点/冻结/解冻 */
    private String changeType;

    /** 变动数量（正增负减） */
    private Integer changeQty;

    /** 变动前数量 */
    private Integer beforeQty;

    /** 变动后数量 */
    private Integer afterQty;

    /** 关联单据号 */
    private String orderNo;

    /** 操作人 */
    private String operator;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;
}
