package com.wms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 质检登记
 */
@Data
public class QualityDTO {

    @NotNull(message = "缺少明细ID")
    private Long itemId;

    /** 1合格 2不合格 */
    @NotNull(message = "缺少质检结果")
    private Integer qualityStatus;

    /** 不合格时填写处理方式：退货/补货 */
    private String remark;
}
