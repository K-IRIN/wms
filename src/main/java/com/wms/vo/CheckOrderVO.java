package com.wms.vo;

import com.wms.entity.WmsCheckItem;
import com.wms.entity.WmsCheckOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 盘点单详情（主表 + 仓库名 + 明细）
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CheckOrderVO extends WmsCheckOrder {

    private String warehouseName;

    private List<ItemVO> items;

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class ItemVO extends WmsCheckItem {
        private String materialCode;
        private String materialName;
        private String unit;
        private String locationCode;
    }
}
