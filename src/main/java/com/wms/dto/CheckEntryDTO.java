package com.wms.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 实盘录入
 */
@Data
public class CheckEntryDTO {

    @NotEmpty(message = "没有要保存的盘点数据")
    private List<Item> items;

    @Data
    public static class Item {
        private Long itemId;
        private Integer actualQty;
        private String reason;
    }
}
