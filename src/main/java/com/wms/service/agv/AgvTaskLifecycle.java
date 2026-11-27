package com.wms.service.agv;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvLog;
import com.wms.entity.WmsAgvTask;
import com.wms.service.WmsAgvDeviceService;
import com.wms.service.WmsAgvLogService;
import com.wms.service.WmsAgvTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.wms.service.AgvEngineService.AGV_IDLE;
import static com.wms.service.AgvEngineService.AGV_RUNNING;
import static com.wms.service.AgvEngineService.AUTO_CHARGE_BATTERY;
import static com.wms.service.AgvEngineService.LOAD_UNLOAD_TICKS;
import static com.wms.service.AgvEngineService.TASK_CANCELED;
import static com.wms.service.AgvEngineService.TASK_DONE;
import static com.wms.service.AgvEngineService.TASK_DROPPING;
import static com.wms.service.AgvEngineService.TASK_PENDING;
import static com.wms.service.AgvEngineService.TASK_PICKING;
import static com.wms.service.AgvEngineService.TASK_RETURNING;
import static com.wms.service.AgvEngineService.TASK_TO_DROP;
import static com.wms.service.AgvEngineService.TASK_TO_PICK;
import static com.wms.service.AgvEngineService.TASK_TRANSITIONS;

/**
 * 任务生命周期（阶段 9）。
 *
 * <p>从 {@code AgvEngineService} 拆出的那一半：状态机、状态推进、回收、日志、路径清理。
 * 引擎只负责"怎么走"（占用表 / 冲突 / 死锁），这里负责"走到哪一步、该记什么"。
 * 对外行为与拆分前逐行一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgvTaskLifecycle {

    /** 任务回收后的冷却时长（毫秒），修 D4：同一任务不会立刻被派回同一台车 */
    public static final long RECYCLE_COOLDOWN_MS = 5_000L;

    private final WmsAgvTaskService taskService;
    private final WmsAgvDeviceService deviceService;
    private final WmsAgvLogService logService;
    private final AgvPublisher publisher;
    private final PathRegistry pathRegistry;

    /** 装卸货倒计时：任务ID → 剩余秒数 */
    private final Map<Long, Integer> waitTicks = new ConcurrentHashMap<>();
    /** 任务回收冷却：taskId → 冷却截止时间戳 */
    private final Map<Long, Long> recycleCooldown = new ConcurrentHashMap<>();
    /** 任务回收避让：taskId → 刚出过问题的 AGV-ID */
    private final Map<Long, Long> recycleAvoidAgv = new ConcurrentHashMap<>();

    /** 引擎提供的"移动"能力：占用判定、冲突处理都在引擎里，生命周期只调用 */
    public interface EngineHooks {
        /** 朝 (tx,ty) 走一步（可能走多格），返回本 tick 实际移动格数 */
        int move(WmsAgvDevice agv, WmsAgvTask task, int tx, int ty);

        /** 清除等待标识（任务完成/回收时） */
        void clearWaiting(Long agvId);

        /** 库位坐标（引擎每 tick 刷新缓存） */
        int[] pos(Long locationId);
    }

    // ==================== 状态机 ====================

    public static String statusName(int status) {
        return switch (status) {
            case TASK_PENDING -> "待分配";
            case TASK_TO_PICK -> "前往取货";
            case TASK_PICKING -> "取货中";
            case TASK_TO_DROP -> "前往卸货";
            case TASK_DROPPING -> "卸货中";
            case TASK_RETURNING -> "返回待命区";
            case TASK_DONE -> "已完成";
            case TASK_CANCELED -> "已取消";
            default -> "未知(" + status + ")";
        };
    }

    public static boolean canTransition(int from, int to) {
        return TASK_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 8 态转换表（供接口/验收脚本读取） */
    public static List<Map<String, Object>> transitionTable() {
        List<Map<String, Object>> rows = new ArrayList<>();
        TASK_TRANSITIONS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("from", e.getKey());
                    row.put("fromName", statusName(e.getKey()));
                    row.put("allowed", e.getValue().stream().sorted()
                            .map(s -> Map.of("status", s, "name", statusName(s)))
                            .toList());
                    rows.add(row);
                });
        return rows;
    }

    /** 断言流转合法，非法则记录"状态变更"日志并抛出明确的 IllegalStateException */
    public void assertTransition(WmsAgvTask task, int target) {
        int from = task.getStatus();
        if (!canTransition(from, target)) {
            String msg = "非法状态流转：" + statusName(from) + "(" + from + ")"
                    + " → " + statusName(target) + "(" + target + ")";
            writeLog(task.getAgvId(), task.getId(), "状态变更",
                    "任务「" + task.getTaskNo() + "」" + msg + "，已拒绝");
            throw new IllegalStateException(msg);
        }
    }

    /** 任务状态变更 + 转换校验 + 日志 + 推送 */
    public void changeTaskStatus(WmsAgvTask task, int newStatus, String logContent) {
        assertTransition(task, newStatus);
        task.setStatus(newStatus);
        taskService.updateById(task);
        writeLog(task.getAgvId(), task.getId(), "状态变更",
                "任务「" + task.getTaskNo() + "」：" + logContent);
        publisher.pushTask(task);
    }

    // ==================== 状态推进 ====================

    /** 推进一步单个任务 */
    public void advanceTask(WmsAgvTask task, WmsAgvDevice agv, EngineHooks hooks) {
        if (agv == null || agv.getStatus() != AGV_RUNNING) {
            return;
        }
        // 在途电量兜底：跌破阈值就让任务回队列、车辆回充，禁止 0% 继续跑（阶段 3.2）
        if (agv.getBattery() != null && agv.getBattery() <= AUTO_CHARGE_BATTERY) {
            recycleTask(task, agv, "在途电量低于 " + AUTO_CHARGE_BATTERY
                    + "%，任务已回收等待改派，车辆回充");
            return;
        }
        switch (task.getStatus()) {
            case TASK_TO_PICK -> {
                int[] from = hooks.pos(task.getFromLocationId());
                int moved = hooks.move(agv, task, from[0], from[1]);
                addDistance(task, moved, true);
                if (agv.getPosX() == from[0] && agv.getPosY() == from[1]) {
                    task.setPickTime(LocalDateTime.now());
                    changeTaskStatus(task, TASK_PICKING, "到达取货点，开始取货");
                    waitTicks.put(task.getId(), LOAD_UNLOAD_TICKS);
                }
            }
            case TASK_PICKING -> {
                if (countDown(task.getId())) {
                    task.setPickDoneTime(LocalDateTime.now());
                    changeTaskStatus(task, TASK_TO_DROP, "取货完成，前往卸货点");
                }
            }
            case TASK_TO_DROP -> {
                int[] to = hooks.pos(task.getToLocationId());
                int moved = hooks.move(agv, task, to[0], to[1]);
                addDistance(task, moved, false);
                if (agv.getPosX() == to[0] && agv.getPosY() == to[1]) {
                    task.setDropTime(LocalDateTime.now());
                    changeTaskStatus(task, TASK_DROPPING, "到达卸货点，开始卸货");
                    waitTicks.put(task.getId(), LOAD_UNLOAD_TICKS);
                }
            }
            case TASK_DROPPING -> {
                if (countDown(task.getId())) {
                    task.setDropDoneTime(LocalDateTime.now());
                    changeTaskStatus(task, TASK_RETURNING, "卸货完成，返回待命区");
                }
            }
            case TASK_RETURNING -> {
                int moved = hooks.move(agv, task, agv.getHomeX(), agv.getHomeY());
                addDistance(task, moved, false);
                if (agv.getPosX() == agv.getHomeX() && agv.getPosY() == agv.getHomeY()) {
                    task.setFinishTime(LocalDateTime.now());
                    changeTaskStatus(task, TASK_DONE, "执行完成，" + agv.getAgvCode() + " 回到待命区");
                    clearPath(agv.getId());
                    agv.setStatus(AGV_IDLE);
                    deviceService.updateById(agv);
                    hooks.clearWaiting(agv.getId());
                    publisher.pushAgv(agv);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 累加任务里程（阶段 5.2）。
     *
     * <p>空驶段 = 前往取货的行驶格数；载货段 = 载货行驶（前往卸货 + 返回待命区）的格数。
     */
    private void addDistance(WmsAgvTask task, int movedCells, boolean emptyRun) {
        if (movedCells <= 0) {
            return;
        }
        int empty = task.getEmptyDistance() == null ? 0 : task.getEmptyDistance();
        int loaded = task.getLoadedDistance() == null ? 0 : task.getLoadedDistance();
        if (emptyRun) {
            empty += movedCells;
        } else {
            loaded += movedCells;
        }
        task.setEmptyDistance(empty);
        task.setLoadedDistance(loaded);
        taskService.updateById(task);
    }

    /** 装卸货倒计时，返回 true 表示倒计时结束 */
    public boolean countDown(Long taskId) {
        int left = waitTicks.getOrDefault(taskId, 0) - 1;
        if (left <= 0) {
            waitTicks.remove(taskId);
            return true;
        }
        waitTicks.put(taskId, left);
        return false;
    }

    public void clearWaitTicks(Long taskId) {
        waitTicks.remove(taskId);
    }

    // ==================== 回收 / 释放 / 冷却 ====================

    public void recycleTask(WmsAgvTask task, WmsAgvDevice agv, String reason) {
        recycleTask(task, agv, reason, null);
    }

    /**
     * 回收任务：在途任务退回待分配队列（保留 priority），AGV 释放为空闲。
     *
     * @param avoidAgvId 挡路的那台车（若有），派单时优先避开
     */
    public void recycleTask(WmsAgvTask task, WmsAgvDevice agv, String reason, Long avoidAgvId) {
        assertTransition(task, TASK_PENDING);
        persistRecycle(task, TASK_PENDING, reason);
        waitTicks.remove(task.getId());
        clearPath(agv.getId());
        agv.setStatus(AGV_IDLE);
        deviceService.updateById(agv);
        // 冷却 + 避让（修 D4：避免活锁）
        markRecycleCooldown(task.getId(), avoidAgvId != null ? avoidAgvId : agv.getId());
        writeLog(agv.getId(), task.getId(), "告警",
                agv.getAgvCode() + " " + reason + "，任务「" + task.getTaskNo() + "」退回待分配（优先级 "
                        + task.getPriority() + " 保留，"
                        + (RECYCLE_COOLDOWN_MS / 1000) + " 秒内不再派给该车）");
        publisher.pushTask(task);
        publisher.pushAgv(agv);
    }

    /** 释放 AGV 回空闲 */
    public void freeAgv(Long agvId) {
        if (agvId == null) {
            return;
        }
        WmsAgvDevice agv = deviceService.getById(agvId);
        if (agv != null && agv.getStatus() == AGV_RUNNING) {
            agv.setStatus(AGV_IDLE);
            deviceService.updateById(agv);
            publisher.pushAgv(agv);
        }
    }

    /** 该任务是否仍处于回收冷却期（修 D4） */
    public boolean isInRecycleCooldown(Long taskId) {
        Long until = recycleCooldown.get(taskId);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            recycleCooldown.remove(taskId);
            recycleAvoidAgv.remove(taskId);
            return false;
        }
        return true;
    }

    /** 冷却期内应优先避开的车（没有则 null） */
    public Long avoidAgv(Long taskId) {
        return recycleAvoidAgv.get(taskId);
    }

    public void clearCooldown(Long taskId) {
        recycleCooldown.remove(taskId);
        recycleAvoidAgv.remove(taskId);
    }

    /** 标记任务回收后的冷却与避让（修 D4） */
    public void markRecycleCooldown(Long taskId, Long agvId) {
        recycleCooldown.put(taskId, System.currentTimeMillis() + RECYCLE_COOLDOWN_MS);
        if (agvId != null) {
            recycleAvoidAgv.put(taskId, agvId);
        }
    }

    /** 实验台重置时清空内存态（阶段 9 把 wait/冷却从引擎搬到这里） */
    public void resetRuntimeState() {
        waitTicks.clear();
        recycleCooldown.clear();
        recycleAvoidAgv.clear();
    }

    /**
     * 回收任务时的落库：显式把 {@code agv_id} 与各阶段时间戳写成 NULL。
     *
     * <p>MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段，所以必须用
     * {@code LambdaUpdateWrapper} 显式 {@code set(col, null)}。
     */
    public void persistRecycle(WmsAgvTask task, int status, String errorMsg) {
        task.setStatus(status);
        task.setAgvId(null);
        task.setErrorMsg(errorMsg);
        task.setStartTime(null);
        task.setPickTime(null);
        task.setPickDoneTime(null);
        task.setDropTime(null);
        task.setDropDoneTime(null);
        task.setFinishTime(null);
        taskService.update(null, Wrappers.<WmsAgvTask>lambdaUpdate()
                .eq(WmsAgvTask::getId, task.getId())
                .set(WmsAgvTask::getStatus, status)
                .set(WmsAgvTask::getAgvId, null)
                .set(WmsAgvTask::getErrorMsg, errorMsg)
                .set(WmsAgvTask::getStartTime, null)
                .set(WmsAgvTask::getPickTime, null)
                .set(WmsAgvTask::getPickDoneTime, null)
                .set(WmsAgvTask::getDropTime, null)
                .set(WmsAgvTask::getDropDoneTime, null)
                .set(WmsAgvTask::getFinishTime, null));
    }

    // ==================== 日志 / 路径 ====================

    public void writeTaskLog(WmsAgvTask task, String logType, String content) {
        writeLog(task.getAgvId(), task.getId(), logType, content);
        publisher.pushTask(task);
    }

    public void writeLog(Long agvId, Long taskId, String logType, String content) {
        WmsAgvLog log = new WmsAgvLog();
        log.setAgvId(agvId);
        log.setTaskId(taskId);
        log.setLogType(logType);
        log.setContent(content);
        logService.save(log);
    }

    public void clearPath(Long agvId) {
        pathRegistry.clear(agvId);
    }
}
