package com.wms.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 供应商 Excel 导入/导出映射
 */
@Data
public class SupplierExcel {

    @ExcelProperty("供应商编号")
    private String supplierCode;

    @ExcelProperty("供应商名称")
    private String supplierName;

    @ExcelProperty("联系人")
    private String contact;

    @ExcelProperty("联系电话")
    private String phone;

    @ExcelProperty("地址")
    private String address;

    @ExcelProperty("备注")
    private String remark;
}
