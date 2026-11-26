package com.wms.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 客户 Excel 导入/导出映射
 */
@Data
public class CustomerExcel {

    @ExcelProperty("客户编号")
    private String customerCode;

    @ExcelProperty("客户名称")
    private String customerName;

    @ExcelProperty("联系人")
    private String contact;

    @ExcelProperty("联系电话")
    private String phone;

    @ExcelProperty("地址")
    private String address;

    @ExcelProperty("合作等级")
    private String level;

    @ExcelProperty("备注")
    private String remark;
}
