package com.wms.agv;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvTask;
import com.wms.service.WmsAgvDeviceService;
import com.wms.service.WmsAgvLogService;
import com.wms.service.WmsAgvTaskService;
import com.wms.service.agv.AgvPublisher;
import com.wms.service.agv.AgvTaskLifecycle;
import com.wms.service.agv.PathRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static com.wms.service.AgvEngineService.AGV_IDLE;
import static com.wms.service.AgvEngineService.AGV_RUNNING;
import static com.wms.service.AgvEngineService.TASK_DONE;
import static com.wms.service.AgvEngineService.TASK_PENDING;
import static com.wms.service.AgvEngineService.TASK_PICKING;
import static com.wms.service.AgvEngineService.TASK_RETURNING;
import static com.wms.service.AgvEngineService.TASK_TO_PICK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgvTaskLifecycle 单测（阶段 9）：状态机、回收、完成、电量兜底、倒计时。
 *
 * <p>全部用 Mockito 替身，不连数据库；验证的是"拆分后行为与拆分前一致"。
 */
class LifecycleTest {

    private WmsAgvTaskService taskService;
    private WmsAgvDeviceService deviceService;
    private WmsAgvLogService logService;
    private AgvPublisher publisher;
    private PathRegistry pathRegistry;
    private AgvTaskLifecycle lifecycle;
    private WmsAgvTask task;
    private WmsAgvDevice agv;

    @BeforeAll
    static void initTableInfo() {
        // 纯单元测试没有 Spring 容器，MyBatis-Plus 的 lambda 列名缓存需要手动初始化一次
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WmsAgvTask.class);
    }

    @BeforeEach
    void setUp() {
        taskService = Mockito.mock(WmsAgvTaskService.class);
        deviceService = Mockito.mock(WmsAgvDeviceService.class);
        logService = Mockito.mock(WmsAgvLogService.class);
        publisher = Mockito.mock(AgvPublisher.class);
        pathRegistry = Mockito.mock(PathRegistry.class);
        when(taskService.updateById(any())).thenReturn(true);
        when(deviceService.updateById(any())).thenReturn(true);
        lifecycle = new AgvTaskLifecycle(taskService, deviceService, logService, publisher, pathRegistry);

        task = new WmsAgvTask();
        task.setId(100L);
        task.setTaskNo("AGVT-TEST-100");
        task.setPriority(1);
        task.setFromLocationId(1L);
        task.setToLocationId(2L);
        task.setAgvId(7L);
        task.setStatus(TASK_TO_PICK);

        agv = new WmsAgvDevice();
        agv.setId(7L);
        agv.setAgvCode("AGV-07");
        agv.setStatus(AGV_RUNNING);
        agv.setBattery(80);
        agv.setPosX(0);
        agv.setPosY(0);
        agv.setHomeX(0);
        agv.setHomeY(0);
    }

    @Test
    void allowsLegalTransition() {
        task.setStatus(TASK_PENDING);
        assertDoesNotThrow(() -> lifecycle.assertTransition(task, TASK_TO_PICK));
    }

    @Test
    void rejectsIllegalTransition() {
        task.setStatus(TASK_PENDING);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> lifecycle.assertTransition(task, TASK_DONE));
        assertTrue(ex.getMessage().contains("非法状态流转"));
    }

    @Test
    void changeTaskStatusPersistsAndPushes() {
        task.setStatus(TASK_TO_PICK);
        lifecycle.changeTaskStatus(task, TASK_PICKING, "到达取货点");
        assertEquals(TASK_PICKING, task.getStatus());
        verify(taskService).updateById(task);
        verify(publisher).pushTask(task);
    }

    @Test
    void recycleReturnsTaskToQueueAndFreesAgv() {
        lifecycle.recycleTask(task, agv, "测试回收");
        assertEquals(TASK_PENDING, task.getStatus());
        assertNull(task.getAgvId());
        assertEquals(AGV_IDLE, agv.getStatus());
        verify(deviceService).updateById(agv);
        verify(publisher).pushTask(task);
        assertTrue(lifecycle.isInRecycleCooldown(task.getId()));
        assertEquals(7L, lifecycle.avoidAgv(task.getId()));
    }

    @Test
    void returningAtHomeCompletesTask() {
        task.setStatus(TASK_RETURNING);
        agv.setPosX(agv.getHomeX());
        agv.setPosY(agv.getHomeY());
        final boolean[] waitingCleared = {false};
        AgvTaskLifecycle.EngineHooks hooks = new AgvTaskLifecycle.EngineHooks() {
            @Override
            public int move(WmsAgvDevice a, WmsAgvTask t, int tx, int ty) {
                return 0;
            }

            @Override
            public void clearWaiting(Long agvId) {
                waitingCleared[0] = true;
            }

            @Override
            public int[] pos(Long locationId) {
                return new int[]{0, 0};
            }
        };

        lifecycle.advanceTask(task, agv, hooks);

        assertEquals(TASK_DONE, task.getStatus());
        assertEquals(AGV_IDLE, agv.getStatus());
        assertTrue(waitingCleared[0]);
        verify(publisher).pushAgv(agv);
    }

    @Test
    void lowBatteryInTransitRecyclesTask() {
        agv.setBattery(5);
        AgvTaskLifecycle.EngineHooks hooks = new AgvTaskLifecycle.EngineHooks() {
            @Override
            public int move(WmsAgvDevice a, WmsAgvTask t, int tx, int ty) {
                return 1;
            }

            @Override
            public void clearWaiting(Long agvId) {
            }

            @Override
            public int[] pos(Long locationId) {
                return new int[]{0, 0};
            }
        };

        lifecycle.advanceTask(task, agv, hooks);

        assertEquals(TASK_PENDING, task.getStatus());
        assertEquals(AGV_IDLE, agv.getStatus());
    }

    @Test
    void countDownStartsChargedAndExpires() {
        // 没有倒计时记录 = 视为已结束（与旧实现 getOrDefault(0) - 1 <= 0 一致）
        assertTrue(lifecycle.countDown(999L));
        // 到达取货点后放入 LOAD_UNLOAD_TICKS 倒计时：第一次返回 false，倒计时结束后为 true
        task.setStatus(TASK_TO_PICK);
        agv.setPosX(0);
        agv.setPosY(0);
        AgvTaskLifecycle.EngineHooks hooks = new AgvTaskLifecycle.EngineHooks() {
            @Override
            public int move(WmsAgvDevice a, WmsAgvTask t, int tx, int ty) {
                return 0;
            }

            @Override
            public void clearWaiting(Long agvId) {
            }

            @Override
            public int[] pos(Long locationId) {
                return new int[]{0, 0};
            }
        };
        lifecycle.advanceTask(task, agv, hooks);
        assertEquals(TASK_PICKING, task.getStatus());
        assertFalse(lifecycle.countDown(task.getId()));
        assertTrue(lifecycle.countDown(task.getId()));
    }
}
