package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物料SKU表（wms_material）
 */
@Data
@TableName("wms_material")
public class WmsMaterial implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 物料编号 */
    private String materialCode;

    /** 物料名称 */
    private String materialName;

    /** 规格型号 */
    private String spec;

    /** 计量单位 */
    private String unit;

    /** 采购单价 */
    private BigDecimal purchasePrice;

    /** 销售单价 */
    private BigDecimal salePrice;

    /** 物料分类ID */
    private Long categoryId;

    /** 保质期（天），NULL表示无保质期 */
    private Integer shelfLife;

    /** 库存上限（预警用） */
    private Integer stockUpper;

    /** 库存下限（预警用） */
    private Integer stockLower;

    /** 状态：0停用 1启用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
