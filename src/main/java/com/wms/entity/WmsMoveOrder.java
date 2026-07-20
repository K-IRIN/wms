package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 移库单表（wms_move_order）
 */
@Data
@TableName("wms_move_order")
public class WmsMoveOrder implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 移库单号，规则YK+日期+序号 */
    private String orderNo;

    /** 物料ID */
    private Long materialId;

    /** 源库位ID */
    private Long fromLocationId;

    /** 目标库位ID */
    private Long toLocationId;

    /** 移库数量 */
    private Integer quantity;

    /** 状态：0待确认 1已完成 */
    private Integer status;

    /** 操作人 */
    private String operator;

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
