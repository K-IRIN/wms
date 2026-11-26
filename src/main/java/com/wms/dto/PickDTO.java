package com.wms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 拣货登记（支持扫码核验）
 */
@Data
public class PickDTO {

    @NotNull(message = "缺少明细ID")
    private Long itemId;

    /** 扫码核验时传入：物料二维码内容（物料编号） */
    private String materialCode;

    /** 手动确认拣货数量；为空表示按出库数量全量拣货 */
    private Integer pickedQty;
}
