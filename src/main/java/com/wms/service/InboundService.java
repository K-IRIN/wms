package com.wms.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.wms.common.BizException;
import com.wms.dto.AuditDTO;
import com.wms.dto.InboundCreateDTO;
import com.wms.dto.QualityDTO;
import com.wms.dto.ShelfDTO;
import com.wms.entity.WmsArea;
import com.wms.entity.WmsInboundItem;
import com.wms.entity.WmsInboundOrder;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsInventoryLog;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsMaterialCategory;
import com.wms.utils.UserContext;
import com.wms.vo.InboundOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购入库核心业务：创建单据 → 质检 → 上架 → 审核生效（库存+流水）
 */
@Service
@RequiredArgsConstructor
public class InboundService {

    private final WmsInboundOrderService orderService;
    private final WmsInboundItemService itemService;
    private final WmsMaterialService materialService;
    private final WmsMaterialCategoryService categoryService;
    private final WmsLocationService locationService;
    private final WmsAreaService areaService;
    private final WmsInventoryService inventoryService;
    private final WmsInventoryLogService inventoryLogService;
    private final WmsSupplierService supplierService;
    private final WmsWarehouseService warehouseService;

    /** 单据状态：0待审核 2已驳回 3已完成 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_DONE = 3;

    // ==================== 创建/修改 ====================

    @Transactional(rollbackFor = Exception.class)
    public String createOrder(InboundCreateDTO dto) {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setOrderNo(genOrderNo());
        order.setSupplierId(dto.getSupplierId());
        order.setWarehouseId(dto.getWarehouseId());
        order.setOrderDate(dto.getOrderDate());
        order.setRemark(dto.getRemark());
        order.setStatus(STATUS_PENDING);
        order.setInvalidFlag(0);
        order.setApplicant(currentName());
        order.setTotalAmount(calcTotal(dto.getItems()));
        orderService.save(order);
        saveItems(order.getId(), dto.getItems());
        return order.getOrderNo();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateOrder(InboundCreateDTO dto) {
        WmsInboundOrder order = getAndCheckEditable(dto.getId());
        long shelved = itemService.lambdaQuery()
                .eq(WmsInboundItem::getOrderId, order.getId())
                .eq(WmsInboundItem::getShelfStatus, 1).count();
        if (shelved > 0) {
            throw new BizException("已有明细上架，无法修改单据");
        }
        order.setSupplierId(dto.getSupplierId());
        order.setWarehouseId(dto.getWarehouseId());
        order.setOrderDate(dto.getOrderDate());
        order.setRemark(dto.getRemark());
        order.setTotalAmount(calcTotal(dto.getItems()));
        order.setStatus(STATUS_PENDING); // 驳回重提：状态回到待审核
        orderService.updateById(order);
        itemService.lambdaUpdate().eq(WmsInboundItem::getOrderId, order.getId()).remove();
        saveItems(order.getId(), dto.getItems());
    }

    private WmsInboundOrder getAndCheckEditable(Long id) {
        WmsInboundOrder order = orderService.getById(id);
        if (order == null) {
            throw new BizException("入库单不存在");
        }
        if (order.getInvalidFlag() != null && order.getInvalidFlag() == 1) {
            throw new BizException("单据已作废，不可操作");
        }
        if (order.getStatus() == STATUS_DONE) {
            throw new BizException("单据已完成，不可修改");
        }
        return order;
    }

    private void saveItems(Long orderId, List<InboundCreateDTO.Item> items) {
        List<WmsInboundItem> list = items.stream().map(i -> {
            WmsInboundItem item = new WmsInboundItem();
            item.setOrderId(orderId);
            item.setMaterialId(i.getMaterialId());
            item.setQuantity(i.getQuantity());
            item.setPrice(i.getPrice());
            item.setAmount(i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())));
            item.setQualityStatus(0);
            item.setShelfStatus(0);
            item.setRemark(i.getRemark());
            return item;
        }).collect(Collectors.toList());
        itemService.saveBatch(list);
    }

    private BigDecimal calcTotal(List<InboundCreateDTO.Item> items) {
        return items.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 单号：RK + yyyyMMdd + 3位当日序号，如 RK20260720001 */
    private String genOrderNo() {
        String prefix = "RK" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = orderService.lambdaQuery().likeRight(WmsInboundOrder::getOrderNo, prefix).count();
        return prefix + String.format("%03d", count + 1);
    }

    // ==================== 详情 ====================

    public InboundOrderVO detail(Long id) {
        WmsInboundOrder order = orderService.getById(id);
        if (order == null) {
            throw new BizException("入库单不存在");
        }
        InboundOrderVO vo = BeanUtil.copyProperties(order, InboundOrderVO.class);
        var supplier = supplierService.getById(order.getSupplierId());
        var warehouse = warehouseService.getById(order.getWarehouseId());
        vo.setSupplierName(supplier == null ? "" : supplier.getSupplierName());
        vo.setWarehouseName(warehouse == null ? "" : warehouse.getWarehouseName());

        List<WmsInboundItem> items = itemService.lambdaQuery()
                .eq(WmsInboundItem::getOrderId, id).orderByAsc(WmsInboundItem::getId).list();
        var materialIds = items.stream().map(WmsInboundItem::getMaterialId)
                .collect(Collectors.toSet());
        Map<Long, WmsMaterial> materialMap = materialIds.isEmpty() ? Map.of()
                : materialService.listByIds(materialIds).stream()
                        .collect(Collectors.toMap(WmsMaterial::getId, m -> m));
        var locationIds = items.stream().map(WmsInboundItem::getLocationId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, WmsLocation> locationMap = locationIds.isEmpty() ? Map.of()
                : locationService.listByIds(locationIds).stream()
                        .collect(Collectors.toMap(WmsLocation::getId, l -> l));
        vo.setItems(items.stream().map(item -> {
            InboundOrderVO.ItemVO iv = BeanUtil.copyProperties(item, InboundOrderVO.ItemVO.class);
            WmsMaterial m = materialMap.get(item.getMaterialId());
            if (m != null) {
                iv.setMaterialCode(m.getMaterialCode());
                iv.setMaterialName(m.getMaterialName());
                iv.setSpec(m.getSpec());
                iv.setUnit(m.getUnit());
            }
            WmsLocation l = item.getLocationId() == null ? null : locationMap.get(item.getLocationId());
            if (l != null) {
                iv.setLocationCode(l.getLocationCode());
            }
            return iv;
        }).collect(Collectors.toList()));
        return vo;
    }

    // ==================== 质检 ====================

    public void quality(QualityDTO dto) {
        WmsInboundItem item = itemService.getById(dto.getItemId());
        if (item == null) {
            throw new BizException("明细不存在");
        }
        checkOrderPending(item.getOrderId(), "质检");
        if (item.getShelfStatus() == 1) {
            throw new BizException("该明细已上架，不能再质检");
        }
        item.setQualityStatus(dto.getQualityStatus());
        item.setRemark(dto.getRemark());
        itemService.updateById(item);
    }

    // ==================== 扫码 ====================

    /** 扫码入库：扫物料二维码（内容为物料编号）自动带出物料信息 */
    public WmsMaterial scanMaterial(String materialCode) {
        WmsMaterial m = materialService.lambdaQuery()
                .eq(WmsMaterial::getMaterialCode, materialCode).one();
        if (m == null) {
            throw new BizException("未找到物料：" + materialCode);
        }
        if (m.getStatus() != 1) {
            throw new BizException("物料已停用：" + m.getMaterialName());
        }
        return m;
    }

    // ==================== 上架 ====================

    /** 智能推荐空闲库位：该仓库下空闲、容量足够、存放类型匹配（或不限类型）的库位，最多推荐5个 */
    public List<WmsLocation> recommendLocations(Long materialId, Long warehouseId) {
        WmsMaterial material = materialService.getById(materialId);
        String categoryName = null;
        if (material != null && material.getCategoryId() != null) {
            WmsMaterialCategory c = categoryService.getById(material.getCategoryId());
            categoryName = c == null ? null : c.getCategoryName();
        }
        List<Long> areaIds = areaService.lambdaQuery()
                .eq(WmsArea::getWarehouseId, warehouseId).eq(WmsArea::getStatus, 1).list()
                .stream().map(WmsArea::getId).collect(Collectors.toList());
        if (areaIds.isEmpty()) {
            return List.of();
        }
        List<WmsLocation> free = locationService.lambdaQuery()
                .in(WmsLocation::getAreaId, areaIds)
                .eq(WmsLocation::getStatus, 0)
                .orderByAsc(WmsLocation::getId).list();
        // 类型匹配（goodsType 为空表示不限）优先，其次按编号排序
        String cn = categoryName;
        return free.stream()
                .sorted((a, b) -> {
                    boolean ma = StrUtil.isBlank(a.getGoodsType()) || a.getGoodsType().equals(cn);
                    boolean mb = StrUtil.isBlank(b.getGoodsType()) || b.getGoodsType().equals(cn);
                    return Boolean.compare(!ma, !mb);
                })
                .limit(5).collect(Collectors.toList());
    }

    /** 仓库下全部空闲库位（手动指定用） */
    public List<WmsLocation> freeLocations(Long warehouseId) {
        List<Long> areaIds = areaService.lambdaQuery()
                .eq(WmsArea::getWarehouseId, warehouseId).list()
                .stream().map(WmsArea::getId).collect(Collectors.toList());
        if (areaIds.isEmpty()) {
            return List.of();
        }
        return locationService.lambdaQuery()
                .in(WmsLocation::getAreaId, areaIds)
                .eq(WmsLocation::getStatus, 0)
                .orderByAsc(WmsLocation::getId).list();
    }

    @Transactional(rollbackFor = Exception.class)
    public void shelf(ShelfDTO dto) {
        WmsInboundItem item = itemService.getById(dto.getItemId());
        if (item == null) {
            throw new BizException("明细不存在");
        }
        checkOrderPending(item.getOrderId(), "上架");
        if (item.getQualityStatus() == null || item.getQualityStatus() == 0) {
            throw new BizException("请先质检，合格后才能上架");
        }
        if (item.getQualityStatus() == 2) {
            throw new BizException("质检不合格的物料不能上架");
        }
        if (item.getShelfStatus() == 1) {
            throw new BizException("该明细已上架");
        }
        WmsLocation location = locationService.getById(dto.getLocationId());
        if (location == null || location.getStatus() != 0) {
            throw new BizException("库位不存在或已被占用");
        }
        item.setLocationId(location.getId());
        item.setShelfStatus(1);
        itemService.updateById(item);
        location.setStatus(1); // 占用
        locationService.updateById(location);
    }

    /** 取消上架（退回库位） */
    @Transactional(rollbackFor = Exception.class)
    public void cancelShelf(Long itemId) {
        WmsInboundItem item = itemService.getById(itemId);
        if (item == null || item.getShelfStatus() != 1) {
            throw new BizException("该明细未上架");
        }
        checkOrderPending(item.getOrderId(), "取消上架");
        WmsLocation location = locationService.getById(item.getLocationId());
        if (location != null) {
            location.setStatus(0);
            locationService.updateById(location);
        }
        // updateById 会忽略 null 字段，库位ID置空必须显式 set
        itemService.lambdaUpdate()
                .eq(WmsInboundItem::getId, itemId)
                .set(WmsInboundItem::getLocationId, null)
                .set(WmsInboundItem::getShelfStatus, 0)
                .update();
    }

    // ==================== 审核 ====================

    @Transactional(rollbackFor = Exception.class)
    public void audit(AuditDTO dto) {
        WmsInboundOrder order = orderService.getById(dto.getId());
        if (order == null) {
            throw new BizException("入库单不存在");
        }
        if (order.getInvalidFlag() != null && order.getInvalidFlag() == 1) {
            throw new BizException("单据已作废，不可审核");
        }
        if (order.getStatus() != STATUS_PENDING) {
            throw new BizException("单据当前状态不可审核");
        }
        // 审核权限：管理员或仓管员
        var roles = UserContext.get().roles();
        if (roles == null || (roles.contains("ADMIN") == false && roles.contains("MANAGER") == false)) {
            throw new BizException(403, "仅管理员或仓管员可审核单据");
        }
        List<WmsInboundItem> items = itemService.lambdaQuery()
                .eq(WmsInboundItem::getOrderId, order.getId()).list();
        order.setAuditor(currentName());
        order.setAuditTime(LocalDateTime.now());
        order.setAuditRemark(dto.getAuditRemark());

        if (Boolean.FALSE.equals(dto.getApprove())) {
            // 驳回：释放已上架库位，允许修改后重提
            for (WmsInboundItem item : items) {
                if (item.getShelfStatus() == 1) {
                    WmsLocation l = locationService.getById(item.getLocationId());
                    if (l != null) {
                        l.setStatus(0);
                        locationService.updateById(l);
                    }
                    itemService.lambdaUpdate()
                            .eq(WmsInboundItem::getId, item.getId())
                            .set(WmsInboundItem::getLocationId, null)
                            .set(WmsInboundItem::getShelfStatus, 0)
                            .update();
                }
            }
            order.setStatus(STATUS_REJECTED);
            orderService.updateById(order);
            return;
        }

        // 通过前校验：全部明细已质检；合格明细已全部上架
        long unChecked = items.stream().filter(i -> i.getQualityStatus() == 0).count();
        if (unChecked > 0) {
            throw new BizException("还有 " + unChecked + " 条明细未质检");
        }
        List<WmsInboundItem> qualified = items.stream()
                .filter(i -> i.getQualityStatus() == 1).collect(Collectors.toList());
        if (qualified.isEmpty()) {
            throw new BizException("没有质检合格的明细，不能审核通过");
        }
        long unShelved = qualified.stream().filter(i -> i.getShelfStatus() != 1).count();
        if (unShelved > 0) {
            throw new BizException("还有 " + unShelved + " 条合格明细未上架");
        }

        // 库存生效 + 写库存流水
        for (WmsInboundItem item : qualified) {
            WmsInventory inv = inventoryService.lambdaQuery()
                    .eq(WmsInventory::getWarehouseId, order.getWarehouseId())
                    .eq(WmsInventory::getLocationId, item.getLocationId())
                    .eq(WmsInventory::getMaterialId, item.getMaterialId())
                    .one();
            int before;
            if (inv == null) {
                inv = new WmsInventory();
                inv.setWarehouseId(order.getWarehouseId());
                inv.setLocationId(item.getLocationId());
                inv.setMaterialId(item.getMaterialId());
                inv.setQuantity(item.getQuantity());
                inv.setFrozenQty(0);
                inventoryService.save(inv);
                before = 0;
            } else {
                before = inv.getQuantity();
                inv.setQuantity(before + item.getQuantity());
                inventoryService.updateById(inv);
            }
            WmsInventoryLog log = new WmsInventoryLog();
            log.setMaterialId(item.getMaterialId());
            log.setWarehouseId(order.getWarehouseId());
            log.setLocationId(item.getLocationId());
            log.setChangeType("入库");
            log.setChangeQty(item.getQuantity());
            log.setBeforeQty(before);
            log.setAfterQty(before + item.getQuantity());
            log.setOrderNo(order.getOrderNo());
            log.setOperator(currentName());
            inventoryLogService.save(log);
        }
        order.setStatus(STATUS_DONE);
        orderService.updateById(order);
    }

    private void checkOrderPending(Long orderId, String action) {
        WmsInboundOrder order = orderService.getById(orderId);
        if (order == null) {
            throw new BizException("入库单不存在");
        }
        if (order.getInvalidFlag() != null && order.getInvalidFlag() == 1) {
            throw new BizException("单据已作废，不可" + action);
        }
        if (order.getStatus() != STATUS_PENDING) {
            throw new BizException("单据当前状态不可" + action);
        }
    }

    private String currentName() {
        var u = UserContext.get();
        return u == null ? "system" : (StrUtil.isNotBlank(u.nickname()) ? u.nickname() : u.username());
    }
}
