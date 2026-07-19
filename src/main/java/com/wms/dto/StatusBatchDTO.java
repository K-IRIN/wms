package com.wms.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量启用/禁用请求
 */
@Data
public class StatusBatchDTO {

    @NotEmpty(message = "请选择要操作的用户")
    private List<Long> ids;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
