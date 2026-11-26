package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 盘点单明细表（wms_check_item）
 */
@Data
@TableName("wms_check_item")
public class WmsCheckItem implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单ID */
    private Long orderId;

    /** 物料ID */
    private Long materialId;

    /** 库位ID */
    private Long locationId;

    /** 账面数量 */
    private Integer bookQty;

    /** 实际数量 */
    private Integer actualQty;

    /** 盈亏数量=实际-账面 */
    private Integer diffQty;

    /** 盈亏原因说明 */
    private String reason;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
