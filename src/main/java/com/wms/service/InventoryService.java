package com.wms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.common.BizException;
import com.wms.entity.WmsArea;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsInventoryLog;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsMoveOrder;
import com.wms.entity.WmsWarehouse;
import com.wms.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存管理核心：多维查询、流水追溯、移库、预警、冻结解冻
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final WmsInventoryService inventoryService;
    private final WmsInventoryLogService inventoryLogService;
    private final WmsMaterialService materialService;
    private final WmsLocationService locationService;
    private final WmsMoveOrderService moveOrderService;
    private final WmsWarehouseService warehouseService;
    private final WmsAreaService areaService;
    private final AgvHookService agvHookService;

    /** 临期阈值：距到期日 <= 30 天进入预警清单 */
    public static final int EXPIRY_WARN_DAYS = 30;

    // ==================== 多维库存查询 ====================

    /** 按物料汇总（总库存维度），可选按仓库过滤 */
    public List<Map<String, Object>> summary(String keyword, Long warehouseId) {
        List<WmsInventory> invs = queryInventory(keyword, warehouseId);
        Map<Long, WmsMaterial> materialMap = materialMap();
        // 按物料聚合
        Map<Long, int[]> agg = new HashMap<>(); // [quantity, frozen]
        for (WmsInventory inv : invs) {
            int[] a = agg.computeIfAbsent(inv.getMaterialId(), k -> new int[2]);
            a[0] += inv.getQuantity();
            a[1] += inv.getFrozenQty();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        agg.forEach((materialId, a) -> rows.add(buildMaterialRow(materialMap.get(materialId), a[0], a[1])));
        rows.sort((x, y) -> String.valueOf(x.get("materialCode")).compareTo(String.valueOf(y.get("materialCode"))));
        return rows;
    }

    /** 按库位明细（库位维度） */
    public List<Map<String, Object>> byLocation(String keyword, Long warehouseId) {
        List<WmsInventory> invs = queryInventory(keyword, warehouseId);
        Map<Long, WmsMaterial> materialMap = materialMap();
        Map<Long, WmsLocation> locationMap = locationService.list().stream()
                .collect(Collectors.toMap(WmsLocation::getId, l -> l));
        Map<Long, WmsWarehouse> warehouseMap = warehouseService.list().stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, w -> w));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WmsInventory inv : invs) {
            Map<String, Object> row = buildMaterialRow(materialMap.get(inv.getMaterialId()),
                    inv.getQuantity(), inv.getFrozenQty());
            row.put("inventoryId", inv.getId());
            WmsLocation l = locationMap.get(inv.getLocationId());
            row.put("locationId", inv.getLocationId());
            row.put("locationCode", l == null ? "" : l.getLocationCode());
            WmsWarehouse w = warehouseMap.get(inv.getWarehouseId());
            row.put("warehouseName", w == null ? "" : w.getWarehouseName());
            row.put("createTime", inv.getCreateTime());
            rows.add(row);
        }
        return rows;
    }

    private List<WmsInventory> queryInventory(String keyword, Long warehouseId) {
        List<Long> materialIds = null;
        if (StrUtil.isNotBlank(keyword)) {
            materialIds = materialService.lambdaQuery()
                    .like(WmsMaterial::getMaterialName, keyword)
                    .or().like(WmsMaterial::getMaterialCode, keyword)
                    .list().stream().map(WmsMaterial::getId).collect(Collectors.toList());
            if (materialIds.isEmpty()) {
                return List.of();
            }
        }
        return inventoryService.lambdaQuery()
                .in(materialIds != null, WmsInventory::getMaterialId, materialIds)
                .eq(warehouseId != null, WmsInventory::getWarehouseId, warehouseId)
                .list();
    }

    private Map<Long, WmsMaterial> materialMap() {
        return materialService.list().stream().collect(Collectors.toMap(WmsMaterial::getId, m -> m));
    }

    /** 构造一行库存数据（含可用量与上下限预警状态） */
    private Map<String, Object> buildMaterialRow(WmsMaterial m, int quantity, int frozen) {
        Map<String, Object> row = new HashMap<>();
        row.put("materialId", m == null ? null : m.getId());
        row.put("materialCode", m == null ? "?" : m.getMaterialCode());
        row.put("materialName", m == null ? "?" : m.getMaterialName());
        row.put("spec", m == null ? "" : m.getSpec());
        row.put("unit", m == null ? "" : m.getUnit());
        row.put("quantity", quantity);
        row.put("frozenQty", frozen);
        row.put("available", quantity - frozen);
        if (m != null) {
            row.put("stockUpper", m.getStockUpper());
            row.put("stockLower", m.getStockLower());
            String warn = "";
            if (m.getStockUpper() != null && quantity > m.getStockUpper()) {
                warn = "超上限";
            } else if (m.getStockLower() != null && quantity < m.getStockLower()) {
                warn = "低于下限";
            }
            row.put("warnType", warn);
        }
        return row;
    }

    // ==================== 流水追溯 ====================

    public List<Map<String, Object>> logs(Long materialId, Long locationId, String changeType, String orderNo) {
        List<WmsInventoryLog> logs = inventoryLogService.lambdaQuery()
                .eq(materialId != null, WmsInventoryLog::getMaterialId, materialId)
                .eq(locationId != null, WmsInventoryLog::getLocationId, locationId)
                .eq(StrUtil.isNotBlank(changeType), WmsInventoryLog::getChangeType, changeType)
                .like(StrUtil.isNotBlank(orderNo), WmsInventoryLog::getOrderNo, orderNo)
                .orderByDesc(WmsInventoryLog::getId)
                .last("LIMIT 500")
                .list();
        Map<Long, WmsMaterial> materialMap = materialMap();
        Map<Long, WmsLocation> locationMap = locationService.list().stream()
                .collect(Collectors.toMap(WmsLocation::getId, l -> l));
        return logs.stream().map(log -> {
            Map<String, Object> row = new HashMap<>();
            row.put("id", log.getId());
            WmsMaterial m = materialMap.get(log.getMaterialId());
            row.put("materialCode", m == null ? "?" : m.getMaterialCode());
            row.put("materialName", m == null ? "?" : m.getMaterialName());
            WmsLocation l = locationMap.get(log.getLocationId());
            row.put("locationCode", l == null ? "" : l.getLocationCode());
            row.put("changeType", log.getChangeType());
            row.put("changeQty", log.getChangeQty());
            row.put("beforeQty", log.getBeforeQty());
            row.put("afterQty", log.getAfterQty());
            row.put("orderNo", log.getOrderNo());
            row.put("operator", log.getOperator());
            row.put("remark", log.getRemark());
            row.put("createTime", log.getCreateTime());
            return row;
        }).collect(Collectors.toList());
    }

    // ==================== 移库 ====================

    @Transactional(rollbackFor = Exception.class)
    public String move(Long materialId, Long fromLocationId, Long toLocationId, Integer quantity, String remark) {
        if (fromLocationId.equals(toLocationId)) {
            throw new BizException("源库位与目标库位不能相同");
        }
        if (quantity == null || quantity <= 0) {
            throw new BizException("移库数量必须大于 0");
        }
        WmsLocation fromLoc = locationService.getById(fromLocationId);
        WmsLocation toLoc = locationService.getById(toLocationId);
        if (fromLoc == null || toLoc == null) {
            throw new BizException("库位不存在");
        }
        if (toLoc.getStatus() == 2) {
            throw new BizException("目标库位已禁用");
        }
        // 源库位库存校验（可用 = 数量 - 冻结）
        WmsInventory fromInv = inventoryService.lambdaQuery()
                .eq(WmsInventory::getLocationId, fromLocationId)
                .eq(WmsInventory::getMaterialId, materialId)
                .one();
        if (fromInv == null || fromInv.getQuantity() - fromInv.getFrozenQty() < quantity) {
            throw new BizException("源库位可用库存不足，无法移库");
        }
        // 源扣减（乐观锁）
        boolean ok = inventoryService.update(Wrappers.<WmsInventory>lambdaUpdate()
                .setSql("quantity = quantity - " + quantity)
                .eq(WmsInventory::getId, fromInv.getId())
                .ge(WmsInventory::getQuantity, quantity + fromInv.getFrozenQty()));
        if (!ok) {
            throw new BizException("源库位库存不足，移库失败（请刷新重试）");
        }
        // 目标累加
        WmsInventory toInv = inventoryService.lambdaQuery()
                .eq(WmsInventory::getLocationId, toLocationId)
                .eq(WmsInventory::getMaterialId, materialId)
                .one();
        int toBefore;
        if (toInv == null) {
            toInv = new WmsInventory();
            toInv.setWarehouseId(toWarehouseOf(toLoc));
            toInv.setLocationId(toLocationId);
            toInv.setMaterialId(materialId);
            toInv.setQuantity(quantity);
            toInv.setFrozenQty(0);
            inventoryService.save(toInv);
            toBefore = 0;
        } else {
            toBefore = toInv.getQuantity();
            toInv.setQuantity(toBefore + quantity);
            inventoryService.updateById(toInv);
        }
        // 移库单
        WmsMoveOrder move = new WmsMoveOrder();
        move.setOrderNo(genMoveNo());
        move.setMaterialId(materialId);
        move.setFromLocationId(fromLocationId);
        move.setToLocationId(toLocationId);
        move.setQuantity(quantity);
        move.setStatus(1);
        move.setOperator(currentName());
        move.setRemark(remark);
        moveOrderService.save(move);
        // AGV 联动：生成移库搬运任务（源库位 → 目标库位）
        agvHookService.onMove(move.getOrderNo(), materialId, fromLocationId, toLocationId);
        // 双向流水
        writeLog(materialId, fromInv.getWarehouseId(), fromLocationId, "移库",
                -quantity, fromInv.getQuantity(), fromInv.getQuantity() - quantity, move.getOrderNo());
        writeLog(materialId, toInv.getWarehouseId(), toLocationId, "移库",
                quantity, toBefore, toBefore + quantity, move.getOrderNo());
        // 库位状态联动：源库位空了→释放；目标库位空闲→占用
        freeLocationIfEmpty(fromLocationId);
        if (toLoc.getStatus() == 0) {
            toLoc.setStatus(1);
            locationService.updateById(toLoc);
        }
        return move.getOrderNo();
    }

    private Long toWarehouseOf(WmsLocation loc) {
        // 库位→库区→仓库
        WmsArea area = areaService.getById(loc.getAreaId());
        return area == null ? null : area.getWarehouseId();
    }

    private void freeLocationIfEmpty(Long locationId) {
        long remain = inventoryService.lambdaQuery()
                .eq(WmsInventory::getLocationId, locationId)
                .gt(WmsInventory::getQuantity, 0).count();
        if (remain == 0) {
            WmsLocation l = locationService.getById(locationId);
            if (l != null && l.getStatus() == 1) {
                l.setStatus(0);
                locationService.updateById(l);
            }
        }
    }

    private void writeLog(Long materialId, Long warehouseId, Long locationId, String type,
                          int changeQty, int before, int after, String orderNo) {
        WmsInventoryLog log = new WmsInventoryLog();
        log.setMaterialId(materialId);
        log.setWarehouseId(warehouseId);
        log.setLocationId(locationId);
        log.setChangeType(type);
        log.setChangeQty(changeQty);
        log.setBeforeQty(before);
        log.setAfterQty(after);
        log.setOrderNo(orderNo);
        log.setOperator(currentName());
        inventoryLogService.save(log);
    }

    private String genMoveNo() {
        String prefix = "YK" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = moveOrderService.lambdaQuery().likeRight(WmsMoveOrder::getOrderNo, prefix).count();
        return prefix + String.format("%03d", count + 1);
    }

    // ==================== 预警 ====================

    public Map<String, Object> warnings() {
        Map<String, Object> result = new HashMap<>();
        // 库存上下限预警（总库存维度）
        List<Map<String, Object>> stockAlerts = summary(null, null).stream()
                .filter(r -> StrUtil.isNotBlank((String) r.get("warnType")))
                .collect(Collectors.toList());
        result.put("stockAlerts", stockAlerts);
        // 临期预警：批次入库时间 + 保质期 <= 30 天
        List<WmsInventory> invs = inventoryService.lambdaQuery()
                .gt(WmsInventory::getQuantity, 0).list();
        Map<Long, WmsMaterial> materialMap = materialMap();
        Map<Long, WmsLocation> locationMap = locationService.list().stream()
                .collect(Collectors.toMap(WmsLocation::getId, l -> l));
        List<Map<String, Object>> expiryAlerts = new ArrayList<>();
        for (WmsInventory inv : invs) {
            WmsMaterial m = materialMap.get(inv.getMaterialId());
            if (m == null || m.getShelfLife() == null || inv.getCreateTime() == null) {
                continue;
            }
            LocalDate expiryDate = inv.getCreateTime().toLocalDate().plusDays(m.getShelfLife());
            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
            if (daysLeft <= EXPIRY_WARN_DAYS) {
                Map<String, Object> row = new HashMap<>();
                row.put("materialCode", m.getMaterialCode());
                row.put("materialName", m.getMaterialName());
                WmsLocation l = locationMap.get(inv.getLocationId());
                row.put("locationCode", l == null ? "" : l.getLocationCode());
                row.put("quantity", inv.getQuantity());
                row.put("shelfLife", m.getShelfLife());
                row.put("inboundDate", inv.getCreateTime().toLocalDate());
                row.put("expiryDate", expiryDate);
                row.put("daysLeft", daysLeft);
                expiryAlerts.add(row);
            }
        }
        expiryAlerts.sort((a, b) -> Long.compare((long) a.get("daysLeft"), (long) b.get("daysLeft")));
        result.put("expiryAlerts", expiryAlerts);
        return result;
    }

    // ==================== 冻结/解冻 ====================

    @Transactional(rollbackFor = Exception.class)
    public void freeze(Long inventoryId, Integer qty, boolean unfreeze, String reason) {
        if (qty == null || qty <= 0) {
            throw new BizException("数量必须大于 0");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException("请填写" + (unfreeze ? "解冻" : "冻结") + "原因");
        }
        WmsInventory inv = inventoryService.getById(inventoryId);
        if (inv == null) {
            throw new BizException("库存记录不存在");
        }
        if (unfreeze) {
            if (inv.getFrozenQty() < qty) {
                throw new BizException("解冻数量超过冻结数量");
            }
            inv.setFrozenQty(inv.getFrozenQty() - qty);
        } else {
            if (inv.getQuantity() - inv.getFrozenQty() < qty) {
                throw new BizException("可冻结数量不足");
            }
            inv.setFrozenQty(inv.getFrozenQty() + qty);
        }
        inventoryService.updateById(inv);
        WmsInventoryLog log = new WmsInventoryLog();
        log.setMaterialId(inv.getMaterialId());
        log.setWarehouseId(inv.getWarehouseId());
        log.setLocationId(inv.getLocationId());
        log.setChangeType(unfreeze ? "解冻" : "冻结");
        log.setChangeQty(unfreeze ? -qty : qty);
        log.setBeforeQty(unfreeze ? inv.getFrozenQty() + qty : inv.getFrozenQty() - qty);
        log.setAfterQty(inv.getFrozenQty());
        log.setOperator(currentName());
        log.setRemark(reason.trim());
        inventoryLogService.save(log);
    }

    private String currentName() {
        var u = UserContext.get();
        return u == null ? "system" : (StrUtil.isNotBlank(u.nickname()) ? u.nickname() : u.username());
    }
}
