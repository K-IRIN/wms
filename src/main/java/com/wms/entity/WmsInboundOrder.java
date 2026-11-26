package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入库单主表（wms_inbound_order）
 */
@Data
@TableName("wms_inbound_order")
public class WmsInboundOrder implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 入库单号，规则RK+日期+序号 */
    private String orderNo;

    /** 供应商ID */
    private Long supplierId;

    /** 入库仓库ID */
    private Long warehouseId;

    /** 入库日期 */
    private LocalDate orderDate;

    /** 入库总金额 */
    private BigDecimal totalAmount;

    /** 单据状态：0待审核 1已审核 2已驳回 3已完成 */
    private Integer status;

    /** 制单人 */
    private String applicant;

    /** 审核人 */
    private String auditor;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 审核意见 */
    private String auditRemark;

    /** 作废标记：0正常 1已作废 */
    private Integer invalidFlag;

    /** 作废原因 */
    private String invalidReason;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
