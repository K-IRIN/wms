package com.wms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建盘点单
 */
@Data
public class CheckCreateDTO {

    @NotNull(message = "请选择盘点仓库")
    private Long warehouseId;

    /** 盘点范围：1按仓库 2按库区 3按物料分类 */
    @NotNull(message = "请选择盘点范围")
    private Integer scopeType;

    /** 按库区时必填 */
    private Long areaId;

    /** 按物料分类时必填 */
    private Long categoryId;

    /** 盘点周期：月度/季度/年度 */
    private String checkPeriod;

    private String remark;
}
