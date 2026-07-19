package com.wms.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 物料 Excel 导入/导出映射
 */
@Data
public class MaterialExcel {

    @ExcelProperty("物料编号")
    private String materialCode;

    @ExcelProperty("物料名称")
    private String materialName;

    @ExcelProperty("规格型号")
    private String spec;

    @ExcelProperty("计量单位")
    private String unit;

    @ExcelProperty("采购单价")
    private BigDecimal purchasePrice;

    @ExcelProperty("销售单价")
    private BigDecimal salePrice;

    @ExcelProperty("保质期(天)")
    private Integer shelfLife;

    @ExcelProperty("库存上限")
    private Integer stockUpper;

    @ExcelProperty("库存下限")
    private Integer stockLower;

    @ExcelProperty("备注")
    private String remark;
}
