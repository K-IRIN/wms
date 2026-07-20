package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 盘点单主表（wms_check_order）
 */
@Data
@TableName("wms_check_order")
public class WmsCheckOrder implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单号，规则PD+日期+序号 */
    private String orderNo;

    /** 盘点仓库ID */
    private Long warehouseId;

    /** 盘点范围：1按仓库 2按库区 3按物料分类 */
    private Integer scopeType;

    /** 库区ID（按库区时必填） */
    private Long areaId;

    /** 物料分类ID（按分类时必填） */
    private Long categoryId;

    /** 盘点周期：月度/季度/年度 */
    private String checkPeriod;

    /** 单据状态：0待盘点 1待审核 2已审核 3已完成 */
    private Integer status;

    /** 盘点人 */
    private String checker;

    /** 审核人 */
    private String auditor;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 审核意见 */
    private String auditRemark;

    /** 备注 */
    private String remark;

    /** 作废标记：0正常 1已作废 */
    private Integer invalidFlag;

    /** 作废原因 */
    private String invalidReason;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
