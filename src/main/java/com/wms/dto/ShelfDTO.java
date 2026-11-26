package com.wms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 上架登记
 */
@Data
public class ShelfDTO {

    @NotNull(message = "缺少明细ID")
    private Long itemId;

    @NotNull(message = "请选择上架库位")
    private Long locationId;
}
