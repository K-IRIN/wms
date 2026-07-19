package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库单明细表（wms_inbound_item）
 */
@Data
@TableName("wms_inbound_item")
public class WmsInboundItem implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 入库单ID */
    private Long orderId;

    /** 物料ID */
    private Long materialId;

    /** 入库数量 */
    private Integer quantity;

    /** 入库单价 */
    private BigDecimal price;

    /** 金额=数量×单价 */
    private BigDecimal amount;

    /** 质检状态：0待质检 1合格 2不合格 */
    private Integer qualityStatus;

    /** 上架库位ID */
    private Long locationId;

    /** 上架状态：0未上架 1已上架 */
    private Integer shelfStatus;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
