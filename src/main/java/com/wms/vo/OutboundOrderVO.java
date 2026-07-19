package com.wms.vo;

import com.wms.entity.WmsOutboundItem;
import com.wms.entity.WmsOutboundOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 出库单详情（主表 + 关联名称 + 明细）
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class OutboundOrderVO extends WmsOutboundOrder {

    private String customerName;
    private String warehouseName;

    /** 各物料在该仓库的可用库存（前端提示用，key=物料ID） */
    private java.util.Map<Long, Integer> stockMap;

    private List<ItemVO> items;

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class ItemVO extends WmsOutboundItem {
        private String materialCode;
        private String materialName;
        private String spec;
        private String unit;
        private String locationCode;
    }
}
