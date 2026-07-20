package com.wms.service;

import com.wms.entity.WmsCheckItem;
import com.wms.entity.WmsCheckOrder;
import com.wms.entity.WmsInboundOrder;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsInventoryLog;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsMaterialCategory;
import com.wms.entity.WmsOutboundOrder;
import com.wms.entity.WmsWarehouse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据统计与可视化分析：为数据看板提供各类统计指标
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final WmsMaterialService materialService;
    private final WmsMaterialCategoryService categoryService;
    private final WmsInventoryService inventoryService;
    private final WmsInventoryLogService inventoryLogService;
    private final WmsInboundOrderService inboundOrderService;
    private final WmsOutboundOrderService outboundOrderService;
    private final WmsCheckOrderService checkOrderService;
    private final WmsCheckItemService checkItemService;
    private final WmsWarehouseService warehouseService;
    private final InventoryService inventoryServiceBiz;

    // ==================== 12.1 顶部统计卡片 + 出入库趋势 ====================

    /** 顶部统计卡片数据 */
    public Map<String, Object> summary() {
        Map<String, Object> result = new HashMap<>();
        result.put("materialCount", materialService.lambdaQuery().eq(WmsMaterial::getStatus, 1).count());
        Integer totalStock = inventoryService.lambdaQuery().list().stream()
                .mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
        result.put("totalStock", totalStock);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        result.put("todayInbound", inboundOrderService.lambdaQuery()
                .eq(WmsInboundOrder::getInvalidFlag, 0)
                .ge(WmsInboundOrder::getCreateTime, todayStart).count());
        result.put("todayOutbound", outboundOrderService.lambdaQuery()
                .eq(WmsOutboundOrder::getInvalidFlag, 0)
                .ge(WmsOutboundOrder::getCreateTime, todayStart).count());

        // 待审核单据数（入库待审核 + 出库待审核 + 盘点待审核）
        long pending = inboundOrderService.lambdaQuery()
                .eq(WmsInboundOrder::getInvalidFlag, 0).eq(WmsInboundOrder::getStatus, 0).count()
                + outboundOrderService.lambdaQuery()
                .eq(WmsOutboundOrder::getInvalidFlag, 0).eq(WmsOutboundOrder::getStatus, 0).count()
                + checkOrderService.lambdaQuery()
                .eq(WmsCheckOrder::getInvalidFlag, 0).eq(WmsCheckOrder::getStatus, 1).count();
        result.put("pendingAudit", pending);

        // 预警数（短缺 + 积压 + 临期）
        Map<String, Object> warnings = inventoryServiceBiz.warnings();
        List<?> stockAlerts = (List<?>) warnings.get("stockAlerts");
        List<?> expiryAlerts = (List<?>) warnings.get("expiryAlerts");
        result.put("warningCount", stockAlerts.size() + expiryAlerts.size());
        return result;
    }

    /**
     * 出入库总量趋势（period: day近30天 / week近12周 / month近12月）
     * 返回 labels + 入库量 + 出库量 + 剩余库存
     */
    public Map<String, Object> inoutTrend(String period) {
        String p = period == null ? "day" : period;
        LocalDate startDate = switch (p) {
            case "week" -> LocalDate.now().minusWeeks(12);
            case "month" -> LocalDate.now().minusMonths(12);
            default -> LocalDate.now().minusDays(30);
        };
        List<WmsInventoryLog> logs = inventoryLogService.lambdaQuery()
                .ge(WmsInventoryLog::getCreateTime, startDate.atStartOfDay())
                .in(WmsInventoryLog::getChangeType, List.of("入库", "出库")).list();

        // 按粒度聚合
        Map<String, Integer> inboundMap = new LinkedHashMap<>();
        Map<String, Integer> outboundMap = new LinkedHashMap<>();
        for (String label : labelsOf(p, startDate)) {
            inboundMap.put(label, 0);
            outboundMap.put(label, 0);
        }
        for (WmsInventoryLog log : logs) {
            String label = labelOf(p, log.getCreateTime().toLocalDate());
            Map<String, Integer> target = "入库".equals(log.getChangeType()) ? inboundMap : outboundMap;
            int qty = log.getChangeQty() == null ? 0 : Math.abs(log.getChangeQty());
            target.merge(label, qty, Integer::sum);
        }
        Integer totalStock = inventoryService.lambdaQuery().list().stream()
                .mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();

        Map<String, Object> result = new HashMap<>();
        result.put("labels", new ArrayList<>(inboundMap.keySet()));
        result.put("inbound", new ArrayList<>(inboundMap.values()));
        result.put("outbound", new ArrayList<>(outboundMap.values()));
        result.put("totalStock", totalStock);
        return result;
    }

    private List<String> labelsOf(String period, LocalDate startDate) {
        List<String> labels = new ArrayList<>();
        LocalDate d = startDate;
        LocalDate now = LocalDate.now();
        switch (period) {
            case "week" -> {
                LocalDate cursor = d.plusWeeks(1);
                while (!cursor.isAfter(now.plusWeeks(1))) {
                    labels.add(weekLabel(cursor));
                    cursor = cursor.plusWeeks(1);
                }
            }
            case "month" -> {
                LocalDate cursor = d.withDayOfMonth(1).plusMonths(1);
                while (!cursor.isAfter(now)) {
                    labels.add(cursor.format(DateTimeFormatter.ofPattern("yyyy-MM")));
                    cursor = cursor.plusMonths(1);
                }
            }
            default -> {
                LocalDate cursor = d.plusDays(1);
                while (!cursor.isAfter(now)) {
                    labels.add(cursor.format(DateTimeFormatter.ofPattern("MM-dd")));
                    cursor = cursor.plusDays(1);
                }
            }
        }
        return labels;
    }

    private String labelOf(String period, LocalDate date) {
        return switch (period) {
            case "week" -> weekLabel(date);
            case "month" -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> date.format(DateTimeFormatter.ofPattern("MM-dd"));
        };
    }

    private String weekLabel(LocalDate date) {
        WeekFields wf = WeekFields.of(Locale.CHINA);
        return date.getYear() + "-W" + date.get(wf.weekOfYear());
    }

    // ==================== 12.2 物料品类占比 ====================

    /** 品类占比：库存占比 + 出入库占比 */
    public Map<String, Object> categoryRatio() {
        List<WmsMaterial> materials = materialService.list();
        Map<Long, WmsMaterial> materialMap = materials.stream()
                .collect(Collectors.toMap(WmsMaterial::getId, m -> m));
        Map<Long, String> categoryMap = categoryService.list().stream()
                .collect(Collectors.toMap(WmsMaterialCategory::getId, WmsMaterialCategory::getCategoryName));

        Function<Long, String> categoryOf = materialId -> {
            WmsMaterial m = materialMap.get(materialId);
            if (m == null || m.getCategoryId() == null) {
                return "未分类";
            }
            return categoryMap.getOrDefault(m.getCategoryId(), "未分类");
        };

        // 库存占比
        Map<String, Integer> stockMap = new HashMap<>();
        for (WmsInventory inv : inventoryService.list()) {
            stockMap.merge(categoryOf.apply(inv.getMaterialId()),
                    inv.getQuantity() == null ? 0 : inv.getQuantity(), Integer::sum);
        }
        // 出入库占比（全周期流水）
        Map<String, Integer> inboundMap = new HashMap<>();
        Map<String, Integer> outboundMap = new HashMap<>();
        for (WmsInventoryLog log : inventoryLogService.lambdaQuery()
                .in(WmsInventoryLog::getChangeType, List.of("入库", "出库")).list()) {
            Map<String, Integer> target = "入库".equals(log.getChangeType()) ? inboundMap : outboundMap;
            target.merge(categoryOf.apply(log.getMaterialId()),
                    log.getChangeQty() == null ? 0 : Math.abs(log.getChangeQty()), Integer::sum);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("stockRatio", toPieData(stockMap));
        result.put("inboundRatio", toPieData(inboundMap));
        result.put("outboundRatio", toPieData(outboundMap));
        return result;
    }

    private List<Map<String, Object>> toPieData(Map<String, Integer> map) {
        return map.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue());
                    return item;
                })
                .sorted((a, b) -> Integer.compare((int) b.get("value"), (int) a.get("value")))
                .collect(Collectors.toList());
    }

    // ==================== 12.3 盘点分析 ====================

    /** 盘点分析：各仓库盘点次数、盈亏次数与总量、准确率 */
    public Map<String, Object> checkAnalysis() {
        List<WmsCheckOrder> orders = checkOrderService.lambdaQuery()
                .eq(WmsCheckOrder::getInvalidFlag, 0).list();
        Map<Long, String> warehouseMap = warehouseService.list().stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName));

        // 各仓库盘点次数
        Map<String, Long> countByWarehouse = orders.stream()
                .collect(Collectors.groupingBy(o -> warehouseMap.getOrDefault(o.getWarehouseId(), "未知"),
                        Collectors.counting()));

        // 盈亏统计（已完成单的明细）
        List<Long> doneOrderIds = orders.stream().filter(o -> o.getStatus() == 3)
                .map(WmsCheckOrder::getId).collect(Collectors.toList());
        List<WmsCheckItem> items = doneOrderIds.isEmpty() ? List.of()
                : checkItemService.lambdaQuery().in(WmsCheckItem::getOrderId, doneOrderIds).list();
        long diffCount = items.stream()
                .filter(i -> i.getDiffQty() != null && i.getDiffQty() != 0).count();
        int diffTotal = items.stream().filter(i -> i.getDiffQty() != null)
                .mapToInt(i -> Math.abs(i.getDiffQty())).sum();
        long profitCount = items.stream().filter(i -> i.getDiffQty() != null && i.getDiffQty() > 0).count();
        long lossCount = items.stream().filter(i -> i.getDiffQty() != null && i.getDiffQty() < 0).count();
        // 准确率 = 无差异明细 / 总明细
        long checkedItems = items.stream().filter(i -> i.getActualQty() != null).count();
        double accuracy = checkedItems == 0 ? 100.0
                : Math.round((checkedItems - diffCount) * 10000.0 / checkedItems) / 100.0;

        Map<String, Object> result = new HashMap<>();
        result.put("totalOrders", orders.size());
        result.put("doneOrders", doneOrderIds.size());
        result.put("countByWarehouse", countByWarehouse);
        result.put("diffCount", diffCount);
        result.put("diffTotal", diffTotal);
        result.put("profitCount", profitCount);
        result.put("lossCount", lossCount);
        result.put("accuracy", accuracy);
        return result;
    }

    // ==================== 12.4 库存周转分析 ====================

    /** 周转分析：高频物料（出库频次TOP）+ 滞销物料（有库存但近N天无出库） */
    public Map<String, Object> turnover(int slowDays) {
        List<WmsMaterial> materials = materialService.list();
        Map<Long, WmsMaterial> materialMap = materials.stream()
                .collect(Collectors.toMap(WmsMaterial::getId, m -> m));

        // 当前库存（按物料汇总）
        Map<Long, Integer> stockMap = new HashMap<>();
        for (WmsInventory inv : inventoryService.list()) {
            stockMap.merge(inv.getMaterialId(), inv.getQuantity() == null ? 0 : inv.getQuantity(), Integer::sum);
        }
        // 出库流水统计
        List<WmsInventoryLog> outLogs = inventoryLogService.lambdaQuery()
                .eq(WmsInventoryLog::getChangeType, "出库").list();
        Map<Long, Long> outCountMap = outLogs.stream()
                .collect(Collectors.groupingBy(WmsInventoryLog::getMaterialId, Collectors.counting()));
        Map<Long, Integer> outQtyMap = new HashMap<>();
        Map<Long, LocalDateTime> lastOutMap = new HashMap<>();
        for (WmsInventoryLog log : outLogs) {
            outQtyMap.merge(log.getMaterialId(), log.getChangeQty() == null ? 0 : Math.abs(log.getChangeQty()), Integer::sum);
            lastOutMap.merge(log.getMaterialId(), log.getCreateTime(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }

        // 高频物料：按出库频次降序
        List<Map<String, Object>> highFreq = outCountMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    WmsMaterial m = materialMap.get(e.getKey());
                    Map<String, Object> row = new HashMap<>();
                    row.put("materialCode", m == null ? "-" : m.getMaterialCode());
                    row.put("materialName", m == null ? "-" : m.getMaterialName());
                    row.put("outCount", e.getValue());
                    row.put("outQty", outQtyMap.getOrDefault(e.getKey(), 0));
                    row.put("currentStock", stockMap.getOrDefault(e.getKey(), 0));
                    return row;
                }).collect(Collectors.toList());

        // 滞销物料：当前有库存，但近 slowDays 天无出库
        LocalDateTime threshold = LocalDateTime.now().minusDays(slowDays);
        List<Map<String, Object>> slowMoving = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : stockMap.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            LocalDateTime lastOut = lastOutMap.get(e.getKey());
            if (lastOut == null || lastOut.isBefore(threshold)) {
                WmsMaterial m = materialMap.get(e.getKey());
                Map<String, Object> row = new HashMap<>();
                row.put("materialCode", m == null ? "-" : m.getMaterialCode());
                row.put("materialName", m == null ? "-" : m.getMaterialName());
                row.put("currentStock", e.getValue());
                row.put("lastOutTime", lastOut == null ? "从未出库" : lastOut.toLocalDate().toString());
                slowMoving.add(row);
            }
        }
        slowMoving.sort(Comparator.comparingInt(r -> -(int) r.get("currentStock")));

        Map<String, Object> result = new HashMap<>();
        result.put("highFreq", highFreq);
        result.put("slowMoving", slowMoving);
        result.put("slowDays", slowDays);
        return result;
    }

    // ==================== 12.5 员工绩效 ====================

    /** 员工绩效：每人入库量/出库量/盘点次数/单据处理量 */
    public List<Map<String, Object>> staffPerformance() {
        Map<String, Map<String, Object>> byUser = new LinkedHashMap<>();

        Function<String, Map<String, Object>> rowOf = name -> byUser.computeIfAbsent(
                name == null || name.isBlank() ? "未知" : name, k -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("operator", k);
                    row.put("inboundQty", 0);
                    row.put("outboundQty", 0);
                    row.put("checkCount", 0);
                    row.put("docCount", 0);
                    return row;
                });

        // 入库量/出库量（流水按操作人）
        for (WmsInventoryLog log : inventoryLogService.list()) {
            Map<String, Object> row = rowOf.apply(log.getOperator());
            if ("入库".equals(log.getChangeType()) && log.getChangeQty() != null) {
                row.put("inboundQty", (int) row.get("inboundQty") + log.getChangeQty());
            } else if ("出库".equals(log.getChangeType()) && log.getChangeQty() != null) {
                row.put("outboundQty", (int) row.get("outboundQty") + Math.abs(log.getChangeQty()));
            }
        }
        // 单据处理量（制单数）
        for (WmsInboundOrder o : inboundOrderService.lambdaQuery().eq(WmsInboundOrder::getInvalidFlag, 0).list()) {
            Map<String, Object> row = rowOf.apply(o.getApplicant());
            row.put("docCount", (int) row.get("docCount") + 1);
        }
        for (WmsOutboundOrder o : outboundOrderService.lambdaQuery().eq(WmsOutboundOrder::getInvalidFlag, 0).list()) {
            Map<String, Object> row = rowOf.apply(o.getApplicant());
            row.put("docCount", (int) row.get("docCount") + 1);
        }
        // 盘点次数
        for (WmsCheckOrder o : checkOrderService.lambdaQuery().eq(WmsCheckOrder::getInvalidFlag, 0).list()) {
            Map<String, Object> row = rowOf.apply(o.getChecker());
            row.put("checkCount", (int) row.get("checkCount") + 1);
        }
        return new ArrayList<>(byUser.values());
    }

    // ==================== 12.6 预警统计 ====================

    /** 预警统计：短缺/积压/临期数量与明细 */
    public Map<String, Object> warningReport() {
        Map<String, Object> warnings = inventoryServiceBiz.warnings();
        List<Map<String, Object>> stockAlerts =
                (List<Map<String, Object>>) warnings.get("stockAlerts");
        List<Map<String, Object>> expiryAlerts =
                (List<Map<String, Object>>) warnings.get("expiryAlerts");
        long shortage = stockAlerts.stream()
                .filter(r -> Objects.equals("低于下限", String.valueOf(r.get("warnType")))).count();
        long overstock = stockAlerts.size() - shortage;

        Map<String, Object> result = new HashMap<>();
        result.put("shortageCount", shortage);
        result.put("overstockCount", overstock);
        result.put("expiryCount", expiryAlerts.size());
        result.put("stockAlerts", stockAlerts);
        result.put("expiryAlerts", expiryAlerts);
        return result;
    }
}
