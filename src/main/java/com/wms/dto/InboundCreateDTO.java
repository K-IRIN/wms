package com.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建/修改入库单
 */
@Data
public class InboundCreateDTO {

    /** 修改时必传 */
    private Long id;

    @NotNull(message = "请选择供应商")
    private Long supplierId;

    @NotNull(message = "请选择入库仓库")
    private Long warehouseId;

    @NotNull(message = "请选择入库日期")
    private LocalDate orderDate;

    private String remark;

    @NotEmpty(message = "入库明细不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "明细缺少物料")
        private Long materialId;
        @NotNull(message = "明细缺少数量")
        private Integer quantity;
        @NotNull(message = "明细缺少单价")
        private BigDecimal price;
        private String remark;
    }
}
