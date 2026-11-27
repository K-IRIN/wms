package com.wms;

import com.wms.dto.AuditDTO;
import com.wms.dto.InboundCreateDTO;
import com.wms.dto.OutboundCreateDTO;
import com.wms.dto.QualityDTO;
import com.wms.dto.ShelfDTO;
import com.wms.entity.WmsCheckItem;
import com.wms.entity.WmsCheckOrder;
import com.wms.entity.WmsInboundItem;
import com.wms.entity.WmsInboundOrder;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsOutboundItem;
import com.wms.entity.WmsOutboundOrder;
import com.wms.service.CheckService;
import com.wms.service.InboundService;
import com.wms.service.OutboundService;
import com.wms.service.WmsCheckItemService;
import com.wms.service.WmsCheckOrderService;
import com.wms.service.WmsInboundItemService;
import com.wms.service.WmsInboundOrderService;
import com.wms.service.WmsInventoryService;
import com.wms.service.WmsLocationService;
import com.wms.service.WmsOutboundItemService;
import com.wms.service.WmsOutboundOrderService;
import com.wms.utils.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阶段 8（W2）库存一致性回归：T2 入库审核只加一次、T3 出库复核只扣一次、T4 盘点按差额修正。
 * 需要一次性库 wms_it：`mvn -Dtest=P0ConsistencyTest -Dwms.it=true test`。
 */
@SpringBootTest
@ActiveProfiles("it")
@EnabledIfSystemProperty(named = "wms.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0ConsistencyTest {

    @Autowired InboundService inboundService;
    @Autowired OutboundService outboundService;
    @Autowired CheckService checkService;
    @Autowired WmsInboundOrderService inboundOrderService;
    @Autowired WmsInboundItemService inboundItemService;
    @Autowired WmsOutboundOrderService outboundOrderService;
    @Autowired WmsOutboundItemService outboundItemService;
    @Autowired WmsCheckOrderService checkOrderService;
    @Autowired WmsCheckItemService checkItemService;
    @Autowired WmsInventoryService inventoryService;
    @Autowired WmsLocationService locationService;

    private static final long WH = 1L;
    private static final long LOC = 1L;
    private static final long MAT = 1L;

    @BeforeEach
    void seed() {
        // 固定基线：库位 1 上物料 1 的库存 = 100、无冻结，库位空闲
        inventoryService.lambdaUpdate()
                .eq(WmsInventory::getWarehouseId, WH)
                .eq(WmsInventory::getLocationId, LOC)
                .eq(WmsInventory::getMaterialId, MAT)
                .remove();
        WmsInventory inv = new WmsInventory();
        inv.setWarehouseId(WH);
        inv.setLocationId(LOC);
        inv.setMaterialId(MAT);
        inv.setQuantity(100);
        inv.setFrozenQty(0);
        inventoryService.save(inv);
        WmsLocation loc = locationService.getById(LOC);
        loc.setStatus(0);
        locationService.updateById(loc);
    }

    // ---------- T2：16 并发审核入库单，库存只能加一次（P0-3） ----------

    @Test
    void t2_inboundAuditAddsStockOnce() throws Exception {
        Long orderId = createInboundOrder(10);
        WmsInboundItem item = inboundItemService.lambdaQuery()
                .eq(WmsInboundItem::getOrderId, orderId).one();
        asAdmin(() -> {
            QualityDTO q = new QualityDTO();
            q.setItemId(item.getId());
            q.setQualityStatus(1);
            inboundService.quality(q);
            ShelfDTO s = new ShelfDTO();
            s.setItemId(item.getId());
            s.setLocationId(LOC);
            inboundService.shelf(s);
            return "ok";
        });

        List<String> errors = runConcurrently(16, () -> {
            AuditDTO a = new AuditDTO();
            a.setId(orderId);
            a.setApprove(true);
            inboundService.audit(a);
            return "ok";
        });

        assertEquals(15, errors.size(), "16 个并发审核只能有 1 个成功：" + errors);
        WmsInventory inv = inventory();
        assertEquals(110, inv.getQuantity(), "100 + 10，且只能加一次");
        assertEquals(0, inv.getFrozenQty(), "入库不产生预占");
        assertEquals(3, inboundOrderService.getById(orderId).getStatus(), "单据应已完成");
    }

    // ---------- T3：16 并发复核出库单，库存只能扣一次且 frozen 归零（P0-4） ----------

    @Test
    void t3_outboundReviewDeductsOnce() throws Exception {
        Long orderId = createAuditedOutboundOrder(30);
        assertEquals(30, inventory().getFrozenQty(), "审核后应预占 30");

        List<String> errors = runConcurrently(16, () -> {
            outboundService.review(orderId);
            return "ok";
        });

        assertEquals(15, errors.size(), "16 个并发复核只能有 1 个成功：" + errors);
        WmsInventory inv = inventory();
        assertEquals(70, inv.getQuantity(), "100 - 30，且只能扣一次");
        assertEquals(0, inv.getFrozenQty(), "复核后预占必须归零");
        assertEquals(3, outboundOrderService.getById(orderId).getStatus(), "单据应已完成");
    }

    // ---------- T4：盘点按差额修正，不覆盖期间发生的库存变化（P0-5） ----------

    @Test
    void t4_checkAppliesDeltaNotSnapshot() {
        // 盘点快照账面 100，实盘 90（diff -10）；审核前又有 +5 入库 → 105
        inventoryService.lambdaUpdate()
                .eq(WmsInventory::getWarehouseId, WH)
                .eq(WmsInventory::getLocationId, LOC)
                .eq(WmsInventory::getMaterialId, MAT)
                .set(WmsInventory::getQuantity, 105)
                .update();

        WmsCheckOrder o = new WmsCheckOrder();
        o.setOrderNo("PDTEST" + System.nanoTime());
        o.setWarehouseId(WH);
        o.setScopeType(1);
        o.setStatus(1);
        o.setInvalidFlag(0);
        checkOrderService.save(o);

        WmsCheckItem it = new WmsCheckItem();
        it.setOrderId(o.getId());
        it.setLocationId(LOC);
        it.setMaterialId(MAT);
        it.setBookQty(100);
        it.setActualQty(90);
        it.setDiffQty(-10);
        it.setReason("阶段8自动化用例");
        checkItemService.save(it);

        asAdmin(() -> {
            AuditDTO a = new AuditDTO();
            a.setId(o.getId());
            a.setApprove(true);
            checkService.audit(a);
            return "ok";
        });

        assertEquals(95, inventory().getQuantity(),
                "105 + (-10)：盘点期间新增的 5 件不能被快照覆盖");
        assertEquals(3, checkOrderService.getById(o.getId()).getStatus(), "盘点单应已完成");
    }

    // ---------- 工具 ----------

    private Long createInboundOrder(int qty) {
        return (Long) asAdmin(() -> {
            InboundCreateDTO dto = new InboundCreateDTO();
            dto.setSupplierId(1L);
            dto.setWarehouseId(WH);
            dto.setOrderDate(LocalDate.now());
            InboundCreateDTO.Item it = new InboundCreateDTO.Item();
            it.setMaterialId(MAT);
            it.setQuantity(qty);
            it.setPrice(BigDecimal.ONE);
            dto.setItems(List.of(it));
            String no = inboundService.createOrder(dto);
            return inboundOrderService.lambdaQuery()
                    .eq(WmsInboundOrder::getOrderNo, no).one().getId();
        });
    }

    /** 建出库单 → 审核（预占）→ 直接置为“已拣货”，供复核用例使用 */
    private Long createAuditedOutboundOrder(int qty) {
        return (Long) asAdmin(() -> {
            OutboundCreateDTO dto = new OutboundCreateDTO();
            dto.setCustomerId(1L);
            dto.setWarehouseId(WH);
            dto.setOrderDate(LocalDate.now());
            OutboundCreateDTO.Item it = new OutboundCreateDTO.Item();
            it.setMaterialId(MAT);
            it.setQuantity(qty);
            it.setPrice(BigDecimal.ONE);
            dto.setItems(List.of(it));
            String no = outboundService.createOrder(dto);
            WmsOutboundOrder o = outboundOrderService.lambdaQuery()
                    .eq(WmsOutboundOrder::getOrderNo, no).one();
            AuditDTO a = new AuditDTO();
            a.setId(o.getId());
            a.setApprove(true);
            outboundService.audit(a);
            WmsOutboundItem oi = outboundItemService.lambdaQuery()
                    .eq(WmsOutboundItem::getOrderId, o.getId()).one();
            oi.setPickedQty(qty);
            outboundItemService.updateById(oi);
            outboundOrderService.lambdaUpdate()
                    .eq(WmsOutboundOrder::getId, o.getId())
                    .set(WmsOutboundOrder::getPickStatus, 2)
                    .update();
            return o.getId();
        });
    }

    private WmsInventory inventory() {
        return inventoryService.lambdaQuery()
                .eq(WmsInventory::getWarehouseId, WH)
                .eq(WmsInventory::getLocationId, LOC)
                .eq(WmsInventory::getMaterialId, MAT)
                .one();
    }

    /** 每个并发任务独立设置 UserContext（ThreadLocal），返回失败信息列表。 */
    private List<String> runConcurrently(int n, Callable<Object> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                UserContext.set(new UserContext.LoginUser(
                        1L, "admin", "管理员", List.of("ADMIN", "MANAGER")));
                try {
                    action.call();
                    return null;
                } catch (Exception e) {
                    return e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    UserContext.clear();
                }
            }));
        }
        start.countDown();
        List<String> errors = new ArrayList<>();
        for (Future<String> f : fs) {
            String err = f.get(60, TimeUnit.SECONDS);
            if (err != null) {
                errors.add(err);
            }
        }
        pool.shutdownNow();
        return errors;
    }

    private Object asAdmin(Callable<Object> action) {
        UserContext.set(new UserContext.LoginUser(
                1L, "admin", "管理员", List.of("ADMIN", "MANAGER")));
        try {
            return action.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            UserContext.clear();
        }
    }
}
