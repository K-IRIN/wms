package com.wms.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.wms.common.BizException;
import com.wms.dto.AuditDTO;
import com.wms.dto.CheckCreateDTO;
import com.wms.dto.CheckEntryDTO;
import com.wms.entity.WmsArea;
import com.wms.entity.WmsCheckItem;
import com.wms.entity.WmsCheckOrder;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsInventoryLog;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsMaterialCategory;
import com.wms.utils.UserContext;
import com.wms.vo.CheckOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 库存盘点核心：创建盘点单（快照账面）→ 实盘录入算盈亏 → 审核修正库存
 */
@Service
@RequiredArgsConstructor
public class CheckService {

    private final WmsCheckOrderService orderService;
    private final WmsCheckItemService itemService;
    private final WmsInventoryService inventoryService;
    private final WmsInventoryLogService inventoryLogService;
    private final WmsMaterialService materialService;
    private final WmsMaterialCategoryService categoryService;
    private final WmsLocationService locationService;
    private final WmsAreaService areaService;
    private final WmsWarehouseService warehouseService;

    /** 状态：0待盘点 1待审核 3已完成 */
    public static final int STATUS_CHECKING = 0;
    public static final int STATUS_AUDITING = 1;
    public static final int STATUS_DONE = 3;

    // ==================== 创建盘点单 ====================

    @Transactional(rollbackFor = Exception.class)
    public String createOrder(CheckCreateDTO dto) {
        // 按范围圈定要盘点的库存记录
        var query = inventoryService.lambdaQuery()
                .eq(WmsInventory::getWarehouseId, dto.getWarehouseId())
                .gt(WmsInventory::getQuantity, 0);
        if (dto.getScopeType() == 2) {
            if (dto.getAreaId() == null) {
                throw new BizException("按库区盘点时必须选择库区");
            }
            List<Long> locationIds = locationService.lambdaQuery()
                    .eq(WmsLocation::getAreaId, dto.getAreaId()).list()
                    .stream().map(WmsLocation::getId).collect(Collectors.toList());
            if (locationIds.isEmpty()) {
                throw new BizException("该库区下没有库位");
            }
            query.in(WmsInventory::getLocationId, locationIds);
        } else if (dto.getScopeType() == 3) {
            if (dto.getCategoryId() == null) {
                throw new BizException("按分类盘点时必须选择物料分类");
            }
            Set<Long> materialIds = materialsInCategory(dto.getCategoryId());
            if (materialIds.isEmpty()) {
                throw new BizException("该分类下没有物料");
            }
            query.in(WmsInventory::getMaterialId, materialIds);
        } else if (dto.getScopeType() != 1) {
            throw new BizException("未知的盘点范围类型");
        }
        List<WmsInventory> invs = query.list();
        if (invs.isEmpty()) {
            throw new BizException("盘点范围内没有库存记录");
        }

        WmsCheckOrder order = new WmsCheckOrder();
        order.setOrderNo(genOrderNo());
        BeanUtil.copyProperties(dto, order);
        order.setStatus(STATUS_CHECKING);
        order.setChecker(currentName());
        orderService.save(order);

        // 快照账面库存
        List<WmsCheckItem> items = invs.stream().map(inv -> {
            WmsCheckItem item = new WmsCheckItem();
            item.setOrderId(order.getId());
            item.setMaterialId(inv.getMaterialId());
            item.setLocationId(inv.getLocationId());
            item.setBookQty(inv.getQuantity());
            return item;
        }).collect(Collectors.toList());
        itemService.saveBatch(items);
        return order.getOrderNo();
    }

    private Set<Long> materialsInCategory(Long categoryId) {
        List<WmsMaterialCategory> all = categoryService.list();
        Map<Long, List<WmsMaterialCategory>> byParent = all.stream()
                .collect(Collectors.groupingBy(WmsMaterialCategory::getParentId));
        Set<Long> categoryIds = new HashSet<>();
        java.util.Deque<Long> stack = new java.util.ArrayDeque<>();
        stack.push(categoryId);
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            if (categoryIds.add(cur)) {
                byParent.getOrDefault(cur, List.of()).forEach(c -> stack.push(c.getId()));
            }
        }
        return materialService.lambdaQuery().in(WmsMaterial::getCategoryId, categoryIds)
                .list().stream().map(WmsMaterial::getId).collect(Collectors.toSet());
    }

    /** 单号：PD + yyyyMMdd + 3位序号 */
    private String genOrderNo() {
        String prefix = "PD" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = orderService.lambdaQuery().likeRight(WmsCheckOrder::getOrderNo, prefix).count();
        return prefix + String.format("%03d", count + 1);
    }

    // ==================== 详情 ====================

    public CheckOrderVO detail(Long id) {
        WmsCheckOrder order = orderService.getById(id);
        if (order == null) {
            throw new BizException("盘点单不存在");
        }
        CheckOrderVO vo = BeanUtil.copyProperties(order, CheckOrderVO.class);
        var warehouse = warehouseService.getById(order.getWarehouseId());
        vo.setWarehouseName(warehouse == null ? "" : warehouse.getWarehouseName());

        List<WmsCheckItem> items = itemService.lambdaQuery()
                .eq(WmsCheckItem::getOrderId, id).orderByAsc(WmsCheckItem::getId).list();
        var materialIds = items.stream().map(WmsCheckItem::getMaterialId).collect(Collectors.toSet());
        Map<Long, WmsMaterial> materialMap = materialIds.isEmpty() ? Map.of()
                : materialService.listByIds(materialIds).stream()
                        .collect(Collectors.toMap(WmsMaterial::getId, m -> m));
        var locationIds = items.stream().map(WmsCheckItem::getLocationId).collect(Collectors.toSet());
        Map<Long, WmsLocation> locationMap = locationIds.isEmpty() ? Map.of()
                : locationService.listByIds(locationIds).stream()
                        .collect(Collectors.toMap(WmsLocation::getId, l -> l));
        vo.setItems(items.stream().map(item -> {
            CheckOrderVO.ItemVO iv = BeanUtil.copyProperties(item, CheckOrderVO.ItemVO.class);
            WmsMaterial m = materialMap.get(item.getMaterialId());
            if (m != null) {
                iv.setMaterialCode(m.getMaterialCode());
                iv.setMaterialName(m.getMaterialName());
                iv.setUnit(m.getUnit());
            }
            WmsLocation l = locationMap.get(item.getLocationId());
            if (l != null) {
                iv.setLocationCode(l.getLocationCode());
            }
            return iv;
        }).collect(Collectors.toList()));
        return vo;
    }

    // ==================== 实盘录入 ====================

    @Transactional(rollbackFor = Exception.class)
    public void entry(CheckEntryDTO dto) {
        for (CheckEntryDTO.Item row : dto.getItems()) {
            WmsCheckItem item = itemService.getById(row.getItemId());
            if (item == null) {
                continue;
            }
            WmsCheckOrder order = orderService.getById(item.getOrderId());
            if (order.getStatus() != STATUS_CHECKING) {
                throw new BizException("盘点单当前状态不可录入");
            }
            if (row.getActualQty() != null && row.getActualQty() < 0) {
                throw new BizException("实际数量不能为负数");
            }
            item.setActualQty(row.getActualQty());
            item.setDiffQty(row.getActualQty() == null ? null : row.getActualQty() - item.getBookQty());
            item.setReason(row.getReason());
            itemService.updateById(item);
        }
    }

    /** 提交审核：全部录入完成 + 差异必填原因 */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long orderId) {
        WmsCheckOrder order = orderService.getById(orderId);
        if (order == null || order.getStatus() != STATUS_CHECKING) {
            throw new BizException("盘点单当前状态不可提交");
        }
        List<WmsCheckItem> items = itemService.lambdaQuery()
                .eq(WmsCheckItem::getOrderId, orderId).list();
        long notEntered = items.stream().filter(i -> i.getActualQty() == null).count();
        if (notEntered > 0) {
            throw new BizException("还有 " + notEntered + " 条明细未录入实际数量");
        }
        long noReason = items.stream()
                .filter(i -> !Objects.equals(i.getDiffQty(), 0) && StrUtil.isBlank(i.getReason())).count();
        if (noReason > 0) {
            throw new BizException("有 " + noReason + " 条盈亏明细未填写原因说明");
        }
        order.setStatus(STATUS_AUDITING);
        orderService.updateById(order);
    }

    // ==================== 审核 ====================

    @Transactional(rollbackFor = Exception.class)
    public void audit(AuditDTO dto) {
        WmsCheckOrder order = orderService.getById(dto.getId());
        if (order == null || order.getStatus() != STATUS_AUDITING) {
            throw new BizException("盘点单当前状态不可审核");
        }
        var roles = UserContext.get().roles();
        if (roles == null || (!roles.contains("ADMIN") && !roles.contains("MANAGER"))) {
            throw new BizException(403, "仅管理员或仓管员可审核");
        }
        order.setAuditor(currentName());
        order.setAuditTime(LocalDateTime.now());
        order.setAuditRemark(dto.getAuditRemark());

        if (Boolean.FALSE.equals(dto.getApprove())) {
            // 驳回 → 退回待盘点，可修改重提
            order.setStatus(STATUS_CHECKING);
            orderService.updateById(order);
            return;
        }

        // 通过：按差异修正库存并写流水
        List<WmsCheckItem> items = itemService.lambdaQuery()
                .eq(WmsCheckItem::getOrderId, order.getId()).list();
        for (WmsCheckItem item : items) {
            if (item.getDiffQty() == null || item.getDiffQty() == 0) {
                continue;
            }
            WmsInventory inv = inventoryService.lambdaQuery()
                    .eq(WmsInventory::getLocationId, item.getLocationId())
                    .eq(WmsInventory::getMaterialId, item.getMaterialId())
                    .one();
            int before = inv == null ? 0 : inv.getQuantity();
            if (inv == null) {
                inv = new WmsInventory();
                inv.setWarehouseId(order.getWarehouseId());
                inv.setLocationId(item.getLocationId());
                inv.setMaterialId(item.getMaterialId());
                inv.setQuantity(item.getActualQty());
                inv.setFrozenQty(0);
                inventoryService.save(inv);
            } else {
                if (inv.getFrozenQty() > item.getActualQty()) {
                    throw new BizException("库位冻结数量大于实盘数量，请先处理冻结库存再修正");
                }
                inv.setQuantity(item.getActualQty());
                inventoryService.updateById(inv);
            }
            WmsInventoryLog log = new WmsInventoryLog();
            log.setMaterialId(item.getMaterialId());
            log.setWarehouseId(order.getWarehouseId());
            log.setLocationId(item.getLocationId());
            log.setChangeType("盘点");
            log.setChangeQty(item.getDiffQty());
            log.setBeforeQty(before);
            log.setAfterQty(item.getActualQty());
            log.setOrderNo(order.getOrderNo());
            log.setOperator(currentName());
            log.setRemark("盘点修正：" + item.getReason());
            inventoryLogService.save(log);
        }
        order.setStatus(STATUS_DONE);
        orderService.updateById(order);
    }

    private String currentName() {
        var u = UserContext.get();
        return u == null ? "system" : (StrUtil.isNotBlank(u.nickname()) ? u.nickname() : u.username());
    }
}
