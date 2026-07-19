package com.wms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单据审核
 */
@Data
public class AuditDTO {

    @NotNull(message = "缺少单据ID")
    private Long id;

    /** true通过 false驳回 */
    @NotNull(message = "缺少审核结果")
    private Boolean approve;

    private String auditRemark;
}
