package com.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单据作废请求
 */
@Data
public class DocCancelDTO {

    /** 单据类型：INBOUND/OUTBOUND/MOVE/CHECK */
    @NotBlank(message = "单据类型不能为空")
    private String type;

    /** 单据ID */
    @NotNull(message = "单据ID不能为空")
    private Long id;

    /** 作废原因（必填） */
    @NotBlank(message = "作废原因不能为空")
    private String reason;
}
