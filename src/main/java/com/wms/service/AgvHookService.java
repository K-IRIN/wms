package com.wms.service;

import com.wms.entity.WmsInboundOrder;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsOutboundItem;
import com.wms.entity.WmsOutboundOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AGV 任务生成钩子（v2.0）：WMS 业务动作 → 自动生成 AGV 搬运任务
 *
 * 解耦设计：WMS 单据与 AGV 任务仅通过【单据号】关联，互不强依赖；
 * 全局开关 agv.enabled=false 时所有钩子直接返回，系统表现与 v1.0 完全一致。
 */
@Service
@RequiredArgsConstructor
public class AgvHookService {

    /** 虚拟月台库位编码：入库搬运的起点 / 出库搬运的终点 */
    private static final String DOCK_CODE = "A01-DOCK";

    private final AgvEngineService engine;
    private final WmsLocationService locationService;
    private final WmsInboundOrderService inboundOrderService;

    @Value("${agv.enabled:true}")
    private boolean enabled;

    /** 缓存月台库位ID */
    private Long dockLocationId;

    // ==================== 入库：上架确认 → 入库搬运任务 ====================

    /** 上架：AGV 把物料从收货月台搬运到目标库位 */
    public void onShelf(Long orderId, Long materialId, Long locationId) {
        if (!enabled) {
            return;
        }
        WmsInboundOrder order = inboundOrderService.getById(orderId);
        String orderNo = order == null ? "-" : order.getOrderNo();
        engine.createTask("入库搬运", orderNo, materialId, dockId(), locationId, 0);
    }

    /** 取消上架：联动取消该明细未完成的入库搬运任务 */
    public void onCancelShelf(Long orderId, Long materialId) {
        if (!enabled) {
            return;
        }
        WmsInboundOrder order = inboundOrderService.getById(orderId);
        String orderNo = order == null ? "-" : order.getOrderNo();
        engine.cancelByTypeAndMaterial("入库搬运", orderNo, materialId, "取消上架，联动取消搬运任务");
    }

    // ==================== 出库：审核生成拣货任务 → 出库搬运任务 ====================

    /** 出库审核通过：为每条已分配库位的明细生成搬运任务（库位 → 发货月台） */
    public void onPickGenerated(WmsOutboundOrder order, List<WmsOutboundItem> items) {
        if (!enabled) {
            return;
        }
        for (WmsOutboundItem item : items) {
            if (item.getLocationId() != null) {
                engine.createTask("出库搬运", order.getOrderNo(), item.getMaterialId(),
                        item.getLocationId(), dockId(), 0);
            }
        }
    }

    // ==================== 移库：移库确认 → 移库搬运任务 ====================

    public void onMove(String orderNo, Long materialId, Long fromLocationId, Long toLocationId) {
        if (!enabled) {
            return;
        }
        engine.createTask("移库搬运", orderNo, materialId, fromLocationId, toLocationId, 0);
    }

    // ==================== 单据作废：联动取消全部未完成任务 ====================

    public void onOrderInvalid(String orderNo) {
        if (!enabled) {
            return;
        }
        engine.cancelByOrderNo(orderNo, "单据已作废，联动取消");
    }

    /** 查询（并缓存）虚拟月台库位ID */
    private Long dockId() {
        if (dockLocationId == null) {
            WmsLocation dock = locationService.lambdaQuery()
                    .eq(WmsLocation::getLocationCode, DOCK_CODE).one();
            dockLocationId = dock == null ? null : dock.getId();
        }
        return dockLocationId;
    }
}
