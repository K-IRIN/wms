package com.wms.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点单导出映射
 */
@Data
public class CheckOrderExcel {

    @ExcelProperty("盘点单号")
    private String orderNo;

    @ExcelProperty("盘点仓库")
    private String warehouseName;

    @ExcelProperty("盘点范围")
    private String scopeText;

    @ExcelProperty("盘点周期")
    private String checkPeriod;

    @ExcelProperty("单据状态")
    private String statusText;

    @ExcelProperty("盘点人")
    private String checker;

    @ExcelProperty("审核人")
    private String auditor;

    @ExcelProperty("审核时间")
    private LocalDateTime auditTime;

    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @ExcelProperty("备注")
    private String remark;
}
