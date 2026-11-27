package com.wms.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.common.BizException;
import com.wms.dto.AuditDTO;
import com.wms.dto.OutboundCreateDTO;
import com.wms.dto.PickDTO;
import com.wms.entity.WmsCustomer;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsInventoryLog;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsOutboundItem;
import com.wms.entity.WmsOutboundOrder;
import com.wms.entity.WmsWarehouse;
import com.wms.utils.UserContext;
import com.wms.vo.OutboundOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 销售出库核心业务：创建单据（库存校验）→ 审核（生成拣货任务）→ 扫码拣货 → 复核扣库存
 */
@Service
@RequiredArgsConstructor
public class OutboundService {

    private final WmsOutboundOrderService orderService;
    private final WmsOutboundItemService itemService;
    private final WmsMaterialService materialService;
    private final WmsLocationService locationService;
    private final WmsInventoryService inventoryService;
    private final WmsInventoryLogService inventoryLogService;
    private final WmsCustomerService customerService;
    private final WmsWarehouseService warehouseService;
    private final AgvHookService agvHookService;

    /** 单据状态：0待审核 1已审核（拣货/复核中） 2已驳回 3已完成 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_AUDITED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_DONE = 3;

    /** 单号唯一键冲突时的最大重试次数 */
    private static final int MAX_ORDER_NO_RETRY = 50;

    // ==================== 创建/修改 ====================

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public String createOrder(OutboundCreateDTO dto) {
        checkStock(dto.getWarehouseId(), dto.getItems());
        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setCustomerId(dto.getCustomerId());
        order.setWarehouseId(dto.getWarehouseId());
        order.setOrderDate(dto.getOrderDate());
        order.setRemark(dto.getRemark());
        order.setStatus(STATUS_PENDING);
        order.setPickStatus(0);
        order.setReviewStatus(0);
        order.setInvalidFlag(0);
        order.setApplicant(currentName());
        order.setTotalAmount(calcTotal(dto.getItems()));
        saveWithRetry(order);
        saveItems(order.getId(), dto.getItems());
        return order.getOrderNo();
    }

    /** 并发下生成单号并落库：唯一键冲突时重取单号重试，最多 50 次（调用方已开启事务） */
    private void saveWithRetry(WmsOutboundOrder order) {
        for (int attempt = 1; attempt <= MAX_ORDER_NO_RETRY; attempt++) {
            order.setId(null);
            order.setOrderNo(genOrderNo());
            try {
                orderService.save(order);
                return;
            } catch (DuplicateKeyException e) {
                // 另一事务已占用该序号：重新取号后重试
            }
        }
        throw new BizException("单号生成冲突，请稍后重试");
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateOrder(OutboundCreateDTO dto) {
        WmsOutboundOrder order = orderService.getById(dto.getId());
        if (order == null) {
            throw new BizException("出库单不存在");
        }
        if (order.getInvalidFlag() != null && order.getInvalidFlag() == 1) {
            throw new BizException("单据已作废，不可修改");
        }
        if (order.getStatus() == STATUS_DONE || order.getStatus() == STATUS_AUDITED) {
            throw new BizException("单据已审核，不可修改");
        }
        checkStock(dto.getWarehouseId(), dto.getItems());
        order.setCustomerId(dto.getCustomerId());
        order.setWarehouseId(dto.getWarehouseId());
        order.setOrderDate(dto.getOrderDate());
        order.setRemark(dto.getRemark());
        order.setTotalAmount(calcTotal(dto.getItems()));
        order.setStatus(STATUS_PENDING);
        orderService.updateById(order);
        itemService.lambdaUpdate().eq(WmsOutboundItem::getOrderId, order.getId()).remove();
        saveItems(order.getId(), dto.getItems());
    }

    /** 库存校验：按仓库汇总可用库存（数量-冻结），不足直接报错——禁止超卖 */
    private void checkStock(Long warehouseId, List<OutboundCreateDTO.Item> items) {
        Map<Long, Integer> need = new HashMap<>();
        for (OutboundCreateDTO.Item i : items) {
            need.merge(i.getMaterialId(), i.getQuantity(), Integer::sum);
        }
        Map<Long, Integer> available = availableStock(warehouseId, need.keySet().stream().toList());
        Map<Long, WmsMaterial> materials = materialService.listByIds(need.keySet()).stream()
                .collect(Collectors.toMap(WmsMaterial::getId, m -> m));
        for (var entry : need.entrySet()) {
            int avail = available.getOrDefault(entry.getKey(), 0);
            if (avail < entry.getValue()) {
                WmsMaterial m = materials.get(entry.getKey());
                throw new BizException("库存不足：" + (m == null ? "物料" + entry.getKey() : m.getMaterialName())
                        + " 需要 " + entry.getValue() + "，可用仅 " + avail);
            }
        }
    }

    /** 各物料在指定仓库的可用库存（quantity - frozenQty 汇总） */
    private Map<Long, Integer> availableStock(Long warehouseId, List<Long> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            return Map.of();
        }
        List<WmsInventory> invs = inventoryService.lambdaQuery()
                .eq(WmsInventory::getWarehouseId, warehouseId)
                .in(WmsInventory::getMaterialId, materialIds)
                .list();
        Map<Long, Integer> result = new HashMap<>();
        for (WmsInventory inv : invs) {
            result.merge(inv.getMaterialId(), inv.getQuantity() - inv.getFrozenQty(), Integer::sum);
        }
        return result;
    }

    private void saveItems(Long orderId, List<OutboundCreateDTO.Item> items) {
        List<WmsOutboundItem> list = items.stream().map(i -> {
            WmsOutboundItem item = new WmsOutboundItem();
            item.setOrderId(orderId);
            item.setMaterialId(i.getMaterialId());
            item.setQuantity(i.getQuantity());
            item.setPickedQty(0);
            item.setPrice(i.getPrice());
            item.setAmount(i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())));
            item.setRemark(i.getRemark());
            return item;
        }).collect(Collectors.toList());
        itemService.saveBatch(list);
    }

    private BigDecimal calcTotal(List<OutboundCreateDTO.Item> items) {
        return items.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 单号：CK + yyyyMMdd + 3位当日序号 */
    /** 单号：CK + yyyyMMdd + 当日最大序号+1，如 CK20260720001 */
    private String genOrderNo() {
        String prefix = "CK" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<Object> rows = orderService.getBaseMapper().selectObjs(new QueryWrapper<WmsOutboundOrder>()
                .select("MAX(CAST(SUBSTRING(order_no, 11) AS UNSIGNED))")
                .likeRight("order_no", prefix));
        Object max = rows.isEmpty() ? null : rows.get(0);
        int next = max == null ? 1 : ((Number) max).intValue() + 1;
        return prefix + String.format("%03d", next);
    }

    // ==================== 详情 ====================

    public OutboundOrderVO detail(Long id) {
        WmsOutboundOrder order = orderService.getById(id);
        if (order == null) {
            throw new BizException("出库单不存在");
        }
        OutboundOrderVO vo = BeanUtil.copyProperties(order, OutboundOrderVO.class);
        WmsCustomer customer = customerService.getById(order.getCustomerId());
        WmsWarehouse warehouse = warehouseService.getById(order.getWarehouseId());
        vo.setCustomerName(customer == null ? "" : customer.getCustomerName());
        vo.setWarehouseName(warehouse == null ? "" : warehouse.getWarehouseName());

        List<WmsOutboundItem> items = itemService.lambdaQuery()
                .eq(WmsOutboundItem::getOrderId, id).orderByAsc(WmsOutboundItem::getId).list();
        var materialIds = items.stream().map(WmsOutboundItem::getMaterialId).collect(Collectors.toSet());
        Map<Long, WmsMaterial> materialMap = materialIds.isEmpty() ? Map.of()
                : materialService.listByIds(materialIds).stream()
                        .collect(Collectors.toMap(WmsMaterial::getId, m -> m));
        var locationIds = items.stream().map(WmsOutboundItem::getLocationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, WmsLocation> locationMap = locationIds.isEmpty() ? Map.of()
                : locationService.listByIds(locationIds).stream()
                        .collect(Collectors.toMap(WmsLocation::getId, l -> l));
        vo.setItems(items.stream().map(item -> {
            OutboundOrderVO.ItemVO iv = BeanUtil.copyProperties(item, OutboundOrderVO.ItemVO.class);
            WmsMaterial m = materialMap.get(item.getMaterialId());
            if (m != null) {
                iv.setMaterialCode(m.getMaterialCode());
                iv.setMaterialName(m.getMaterialName());
                iv.setSpec(m.getSpec());
                iv.setUnit(m.getUnit());
            }
            if (item.getLocationId() != null) {
                WmsLocation l = locationMap.get(item.getLocationId());
                if (l != null) {
                    iv.setLocationCode(l.getLocationCode());
                }
            }
            return iv;
        }).collect(Collectors.toList()));
        vo.setStockMap(availableStock(order.getWarehouseId(), materialIds.stream().toList()));
        return vo;
    }

    // ==================== 审核（生成拣货任务） ====================

    @Transactional(rollbackFor = Exception.class)
    public void audit(AuditDTO dto) {
        WmsOutboundOrder order = orderService.getById(dto.getId());
        if (order == null) {
            throw new BizException("出库单不存在");
        }
        if (order.getInvalidFlag() != null && order.getInvalidFlag() == 1) {
            throw new BizException("单据已作废，不可审核");
        }
        if (order.getStatus() != STATUS_PENDING) {
            throw new BizException("单据当前状态不可审核");
        }
        order.setAuditor(currentName());
        order.setAuditTime(LocalDateTime.now());
        order.setAuditRemark(dto.getAuditRemark());

        if (Boolean.FALSE.equals(dto.getApprove())) {
            order.setStatus(STATUS_REJECTED);
            orderService.updateById(order);
            return;
        }

        // 再次校验库存（防止制单后库存被其他出库占用）
        List<WmsOutboundItem> items = itemService.lambdaQuery()
                .eq(WmsOutboundItem::getOrderId, order.getId()).list();
        checkStock(order.getWarehouseId(), items.stream().map(i -> {
            OutboundCreateDTO.Item x = new OutboundCreateDTO.Item();
            x.setMaterialId(i.getMaterialId());
            x.setQuantity(i.getQuantity());
            x.setPrice(i.getPrice());
            return x;
        }).collect(Collectors.toList()));

        // 自动生成拣货任务：为每条明细分配库位（够用库位中库存最少的优先，减少碎片）
        for (WmsOutboundItem item : items) {
            List<WmsInventory> candidates = inventoryService.lambdaQuery()
                    .eq(WmsInventory::getWarehouseId, order.getWarehouseId())
                    .eq(WmsInventory::getMaterialId, item.getMaterialId())
                    .list().stream()
                    .filter(inv -> inv.getQuantity() - inv.getFrozenQty() >= item.getQuantity())
                    .sorted((a, b) -> Integer.compare(a.getQuantity(), b.getQuantity()))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                WmsMaterial m = materialService.getById(item.getMaterialId());
                throw new BizException("物料「" + (m == null ? item.getMaterialId().toString() : m.getMaterialName())
                        + "」无单一库位库存满足 " + item.getQuantity() + "，无法生成拣货任务");
            }
            item.setLocationId(candidates.get(0).getLocationId());
            itemService.updateById(item);
        }
        order.setPickStatus(0);
        order.setStatus(STATUS_AUDITED);
        orderService.updateById(order);
        // AGV 联动：为每条拣货明细生成出库搬运任务（拣货库位 → 发货月台）
        agvHookService.onPickGenerated(order, items);
    }

    // ==================== 拣货（扫码核验） ====================

    @Transactional(rollbackFor = Exception.class)
    public void pick(PickDTO dto) {
        WmsOutboundItem item = itemService.getById(dto.getItemId());
        if (item == null) {
            throw new BizException("明细不存在");
        }
        WmsOutboundOrder order = orderService.getById(item.getOrderId());
        if (order.getStatus() != STATUS_AUDITED) {
            throw new BizException("单据未审核通过，不能拣货");
        }
        if (item.getPickedQty() >= item.getQuantity()) {
            throw new BizException("该明细已拣货完成");
        }
        // 扫码核验：比对二维码内容与明细物料是否一致
        if (StrUtil.isNotBlank(dto.getMaterialCode())) {
            WmsMaterial m = materialService.getById(item.getMaterialId());
            if (m == null || !m.getMaterialCode().equals(dto.getMaterialCode())) {
                throw new BizException("扫码物料与出库明细不符，禁止出库（扫的是 "
                        + dto.getMaterialCode() + "）");
            }
        }
        int target = dto.getPickedQty() == null ? item.getQuantity() : dto.getPickedQty();
        if (target > item.getQuantity() || target <= 0) {
            throw new BizException("拣货数量不合法");
        }
        item.setPickedQty(target);
        itemService.updateById(item);

        // 更新整单拣货状态
        List<WmsOutboundItem> all = itemService.lambdaQuery()
                .eq(WmsOutboundItem::getOrderId, order.getId()).list();
        boolean allPicked = all.stream().allMatch(i -> i.getPickedQty() >= i.getQuantity());
        boolean anyPicked = all.stream().anyMatch(i -> i.getPickedQty() > 0);
        order.setPickStatus(allPicked ? 2 : anyPicked ? 1 : 0);
        orderService.updateById(order);
    }

    // ==================== 复核（扣库存） ====================

    @Transactional(rollbackFor = Exception.class)
    public void review(Long orderId) {
        WmsOutboundOrder order = orderService.getById(orderId);
        if (order == null) {
            throw new BizException("出库单不存在");
        }
        if (order.getStatus() != STATUS_AUDITED) {
            throw new BizException("单据当前状态不可复核");
        }
        if (order.getPickStatus() != 2) {
            throw new BizException("拣货未完成，不能复核");
        }
        List<WmsOutboundItem> items = itemService.lambdaQuery()
                .eq(WmsOutboundItem::getOrderId, orderId).list();
        for (WmsOutboundItem item : items) {
            WmsInventory inv = inventoryService.lambdaQuery()
                    .eq(WmsInventory::getWarehouseId, order.getWarehouseId())
                    .eq(WmsInventory::getLocationId, item.getLocationId())
                    .eq(WmsInventory::getMaterialId, item.getMaterialId())
                    .one();
            if (inv == null) {
                throw new BizException("库存记录不存在，数据异常");
            }
            // 乐观锁扣减：quantity - frozen >= 需求 才能更新成功，防止并发超卖/负库存
            boolean ok = inventoryService.update(Wrappers.<WmsInventory>lambdaUpdate()
                    .setSql("quantity = quantity - " + item.getQuantity())
                    .eq(WmsInventory::getId, inv.getId())
                    .ge(WmsInventory::getQuantity, item.getQuantity() + inv.getFrozenQty()));
            if (!ok) {
                WmsMaterial m = materialService.getById(item.getMaterialId());
                throw new BizException("库存不足，复核失败：" + (m == null ? "" : m.getMaterialName())
                        + "（可能刚被其他出库占用，请刷新重试）");
            }
            WmsInventoryLog log = new WmsInventoryLog();
            log.setMaterialId(item.getMaterialId());
            log.setWarehouseId(order.getWarehouseId());
            log.setLocationId(item.getLocationId());
            log.setChangeType("出库");
            log.setChangeQty(-item.getQuantity());
            log.setBeforeQty(inv.getQuantity());
            log.setAfterQty(inv.getQuantity() - item.getQuantity());
            log.setOrderNo(order.getOrderNo());
            log.setOperator(currentName());
            inventoryLogService.save(log);
        }
        // 库位中所有物料库存都为 0 时，库位释放为空闲
        var locationIds = items.stream().map(WmsOutboundItem::getLocationId).collect(Collectors.toSet());
        for (Long locId : locationIds) {
            long remain = inventoryService.lambdaQuery()
                    .eq(WmsInventory::getLocationId, locId)
                    .gt(WmsInventory::getQuantity, 0).count();
            if (remain == 0) {
                WmsLocation l = locationService.getById(locId);
                if (l != null && l.getStatus() == 1) {
                    l.setStatus(0);
                    locationService.updateById(l);
                }
            }
        }
        order.setReviewStatus(1);
        order.setStatus(STATUS_DONE);
        orderService.updateById(order);
    }

    private String currentName() {
        var u = UserContext.get();
        return u == null ? "system" : (StrUtil.isNotBlank(u.nickname()) ? u.nickname() : u.username());
    }
}
