package com.wms.vo;

import com.wms.entity.WmsInboundItem;
import com.wms.entity.WmsInboundOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 入库单详情（主表 + 关联名称 + 明细）
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class InboundOrderVO extends WmsInboundOrder {

    private String supplierName;
    private String warehouseName;

    private List<ItemVO> items;

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class ItemVO extends WmsInboundItem {
        private String materialCode;
        private String materialName;
        private String spec;
        private String unit;
        private String locationCode;
    }
}
