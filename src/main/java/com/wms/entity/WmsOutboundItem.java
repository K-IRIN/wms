package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库单明细表（wms_outbound_item）
 */
@Data
@TableName("wms_outbound_item")
public class WmsOutboundItem implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 出库单ID */
    private Long orderId;

    /** 物料ID */
    private Long materialId;

    /** 拣货库位ID */
    private Long locationId;

    /** 出库数量 */
    private Integer quantity;

    /** 已拣数量 */
    private Integer pickedQty;

    /** 出库单价 */
    private BigDecimal price;

    /** 金额=数量×单价 */
    private BigDecimal amount;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
