package com.wms;

import com.wms.dto.InboundCreateDTO;
import com.wms.entity.WmsInboundOrder;
import com.wms.service.InboundService;
import com.wms.service.WmsInboundOrderService;
import com.wms.utils.JwtUtils;
import com.wms.utils.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 红→绿用例（需要一次性库 wms_it，见 docs/改进实施计划.md 测试约定）。
 *
 * <p>运行：{@code mvn -Dtest=P0ConcurrencyTest#t1_orderNo+P0ConcurrencyTest#t5_guest -Dwms.it=true test}
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@EnabledIfSystemProperty(named = "wms.it", matches = "true")
class P0ConcurrencyTest {

    private static final UserContext.LoginUser ADMIN =
            new UserContext.LoginUser(1L, "admin", "管理员", List.of("ADMIN", "MANAGER"));

    @Autowired
    private InboundService inboundService;

    @Autowired
    private WmsInboundOrderService inboundOrderService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private MockMvc mockMvc;

    /** T1：32 线程同时建入库单，0 失败且单号互不重复 */
    @Test
    @DisplayName("T1 32 并发建单：0 失败、单号唯一")
    void t1_orderNo() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> orderNos = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                UserContext.set(ADMIN);
                try {
                    start.await();
                    String orderNo = inboundService.createOrder(newInboundOrder());
                    orderNos.add(orderNo);
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    UserContext.clear();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(failures.isEmpty(),
                () -> "并发建单出现失败（" + failures.size() + " 个）：" + firstMessage(failures));
        assertEquals(threads, orderNos.size(), "成功建单数应等于线程数");

        Set<String> distinct = new HashSet<>(orderNos);
        assertEquals(threads, distinct.size(), "单号出现重复：" + orderNos);

        long saved = inboundOrderService.lambdaQuery()
                .in(WmsInboundOrder::getOrderNo, distinct)
                .count();
        assertEquals(threads, saved, "库中按单号查到的单据数应等于线程数");
    }

    /** T5：GUEST 越权新增仓库，必须被 @RequireRole 拦下（body code=403） */
    @Test
    @DisplayName("T5 GUEST 新增仓库返回 403")
    void t5_guest() throws Exception {
        String guestToken = jwtUtils.createToken(4L, "guest");

        mockMvc.perform(post("/base/warehouse/add")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseCode\":\"WH-GUEST-T5\",\"warehouseName\":\"GUEST越权测试仓\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private static InboundCreateDTO newInboundOrder() {
        InboundCreateDTO.Item item = new InboundCreateDTO.Item();
        item.setMaterialId(1L);
        item.setQuantity(1);
        item.setPrice(BigDecimal.TEN);

        InboundCreateDTO dto = new InboundCreateDTO();
        dto.setSupplierId(1L);
        dto.setWarehouseId(1L);
        dto.setOrderDate(LocalDate.now());
        dto.setRemark("并发单号用例");
        dto.setItems(List.of(item));
        return dto;
    }

    private static String firstMessage(ConcurrentLinkedQueue<Throwable> failures) {
        Throwable first = failures.peek();
        return first == null ? "无" : first.getClass().getSimpleName() + ": " + first.getMessage();
    }
}
