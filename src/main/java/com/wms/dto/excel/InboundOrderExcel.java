package com.wms.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入库单导出映射
 */
@Data
public class InboundOrderExcel {

    @ExcelProperty("入库单号")
    private String orderNo;

    @ExcelProperty("供应商")
    private String supplierName;

    @ExcelProperty("入库仓库")
    private String warehouseName;

    @ExcelProperty("入库日期")
    private LocalDate orderDate;

    @ExcelProperty("总金额")
    private BigDecimal totalAmount;

    @ExcelProperty("单据状态")
    private String statusText;

    @ExcelProperty("制单人")
    private String applicant;

    @ExcelProperty("审核人")
    private String auditor;

    @ExcelProperty("审核时间")
    private LocalDateTime auditTime;

    @ExcelProperty("备注")
    private String remark;
}
