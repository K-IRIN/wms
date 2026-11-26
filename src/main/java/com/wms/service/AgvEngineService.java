package com.wms.service;

import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvLog;
import com.wms.entity.WmsAgvTask;
import com.wms.entity.WmsLocation;
import com.wms.websocket.AgvWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGV 调度引擎（v2.0 WCS 模拟核心）
 *
 * 用"每秒一次的定时器 + 状态机"模拟真实 WCS 的 AGV 硬件调度，无需任何真实设备：
 *  1. 任务分配：待分配任务按【紧急优先 → 创建时间】排序，分配给【空闲、电量充足、距离取货点最近】的 AGV
 *  2. 状态推进：待分配→前往取货→取货中→前往卸货→卸货中→返回待命区→已完成
 *  3. 路径模拟：库位坐标按曼哈顿路径逐格移动，每格耗电 1%
 *  4. 充电模拟：空闲低电量自动充电，充电中每秒恢复 10%
 *  5. 异常模拟：故障停机自动回收任务并重新分配给其他空闲 AGV
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgvEngineService {

    // ==================== 任务状态机 ====================
    /** 待分配 */
    public static final int TASK_PENDING = 0;
    /** 前往取货 */
    public static final int TASK_TO_PICK = 1;
    /** 取货中 */
    public static final int TASK_PICKING = 2;
    /** 前往卸货 */
    public static final int TASK_TO_DROP = 3;
    /** 卸货中 */
    public static final int TASK_DROPPING = 4;
    /** 返回待命区 */
    public static final int TASK_RETURNING = 5;
    /** 已完成 */
    public static final int TASK_DONE = 6;
    /** 已取消 */
    public static final int TASK_CANCELED = 7;

    // ==================== AGV 状态 ====================
    /** 空闲 */
    public static final int AGV_IDLE = 0;
    /** 运行中 */
    public static final int AGV_RUNNING = 1;
    /** 充电中 */
    public static final int AGV_CHARGING = 2;
    /** 故障 */
    public static final int AGV_FAULT = 3;

    /** 可取任务的最低电量（低于此电量不再派单） */
    private static final int MIN_ASSIGN_BATTERY = 20;
    /** 空闲自动充电的电量阈值 */
    private static final int AUTO_CHARGE_BATTERY = 10;
    /** 每移动 1 格耗电（%） */
    private static final int BATTERY_PER_CELL = 1;
    /** 充电中每秒恢复电量（%） */
    private static final int CHARGE_PER_TICK = 10;
    /** 取货/卸货动作耗时（秒） */
    private static final int LOAD_UNLOAD_TICKS = 2;

    private final WmsAgvDeviceService deviceService;
    private final WmsAgvTaskService taskService;
    private final WmsAgvLogService logService;
    private final WmsLocationService locationService;
    private final AgvWebSocketHandler wsHandler;

    /** 全局开关：false 时引擎休眠、不生成任务，系统表现与 v1.0 一致 */
    @Value("${agv.enabled:true}")
    private boolean enabled;

    /** 移动累计器：AGV-ID → 已累计的可移动格数（支持 0.8 格/秒这类小速度） */
    private final Map<Long, Double> moveAcc = new ConcurrentHashMap<>();
    /** 装卸货倒计时：任务ID → 剩余秒数 */
    private final Map<Long, Integer> waitTicks = new ConcurrentHashMap<>();
    /** 通道堵塞倒计时：AGV-ID → 剩余堵塞秒数 */
    private final Map<Long, Integer> blockTicks = new ConcurrentHashMap<>();
    /** 库位坐标缓存（库位ID → [x, y]），每秒重建一次，数据量小 */
    private final Map<Long, int[]> locationPosCache = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== 主循环：每秒推进一次 ====================

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            refreshLocationCache();
            blockTick();
            chargingTick();
            autoChargeTick();
            assignPendingTasks();
            progressRunningTasks();
        } catch (Exception e) {
            // 引擎异常不能影响主系统，记日志即可
            log.error("AGV 调度引擎异常：{}", e.getMessage(), e);
        }
    }

    // ==================== 任务分配 ====================

    /** 待分配任务 → 挑选最优空闲 AGV（紧急任务优先，距离最近优先） */
    private void assignPendingTasks() {
        List<WmsAgvTask> pending = taskService.lambdaQuery()
                .eq(WmsAgvTask::getStatus, TASK_PENDING)
                .orderByDesc(WmsAgvTask::getPriority)
                .orderByAsc(WmsAgvTask::getId)
                .list();
        if (pending.isEmpty()) {
            return;
        }
        List<WmsAgvDevice> idleAgvs = deviceService.lambdaQuery()
                .eq(WmsAgvDevice::getEnabled, 1)
                .eq(WmsAgvDevice::getStatus, AGV_IDLE)
                .gt(WmsAgvDevice::getBattery, MIN_ASSIGN_BATTERY)
                .list();
        for (WmsAgvTask task : pending) {
            if (idleAgvs.isEmpty()) {
                return;
            }
            int[] from = resolvePos(task.getFromLocationId());
            // 距离取货点曼哈顿距离最近的 AGV 优先
            WmsAgvDevice best = idleAgvs.stream()
                    .min(Comparator.comparingInt(a -> manhattan(a.getPosX(), a.getPosY(), from[0], from[1])))
                    .orElse(null);
            if (best == null) {
                return;
            }
            task.setAgvId(best.getId());
            task.setStatus(TASK_TO_PICK);
            task.setStartTime(LocalDateTime.now());
            taskService.updateById(task);
            best.setStatus(AGV_RUNNING);
            deviceService.updateById(best);
            idleAgvs.remove(best);
            writeLog(best.getId(), task.getId(), "状态变更",
                    "任务「" + task.getTaskNo() + "」分配给 " + best.getAgvCode() + "，前往取货");
            pushTask(task);
            pushAgv(best);
        }
    }

    // ==================== 状态机推进 ====================

    private void progressRunningTasks() {
        List<WmsAgvTask> running = taskService.lambdaQuery()
                .between(WmsAgvTask::getStatus, TASK_TO_PICK, TASK_RETURNING)
                .isNotNull(WmsAgvTask::getAgvId)
                .list();
        for (WmsAgvTask task : running) {
            WmsAgvDevice agv = deviceService.getById(task.getAgvId());
            if (agv == null || agv.getStatus() != AGV_RUNNING) {
                continue;
            }
            // 通道堵塞：原地暂停
            if (blockTicks.getOrDefault(agv.getId(), 0) > 0) {
                continue;
            }
            switch (task.getStatus()) {
                case TASK_TO_PICK -> {
                    int[] from = resolvePos(task.getFromLocationId());
                    if (moveToward(agv, from[0], from[1])) {
                        changeTaskStatus(task, TASK_PICKING, "到达取货点，开始取货");
                        waitTicks.put(task.getId(), LOAD_UNLOAD_TICKS);
                    }
                }
                case TASK_PICKING -> {
                    if (countDown(task.getId())) {
                        changeTaskStatus(task, TASK_TO_DROP, "取货完成，前往卸货点");
                    }
                }
                case TASK_TO_DROP -> {
                    int[] to = resolvePos(task.getToLocationId());
                    if (moveToward(agv, to[0], to[1])) {
                        changeTaskStatus(task, TASK_DROPPING, "到达卸货点，开始卸货");
                        waitTicks.put(task.getId(), LOAD_UNLOAD_TICKS);
                    }
                }
                case TASK_DROPPING -> {
                    if (countDown(task.getId())) {
                        changeTaskStatus(task, TASK_RETURNING, "卸货完成，返回待命区");
                    }
                }
                case TASK_RETURNING -> {
                    if (moveToward(agv, agv.getHomeX(), agv.getHomeY())) {
                        task.setStatus(TASK_DONE);
                        task.setFinishTime(LocalDateTime.now());
                        taskService.updateById(task);
                        agv.setStatus(AGV_IDLE);
                        deviceService.updateById(agv);
                        writeLog(agv.getId(), task.getId(), "状态变更",
                                "任务「" + task.getTaskNo() + "」执行完成，" + agv.getAgvCode() + " 回到待命区");
                        pushTask(task);
                        pushAgv(agv);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** 任务状态变更 + 日志 + 推送 */
    private void changeTaskStatus(WmsAgvTask task, int newStatus, String logContent) {
        task.setStatus(newStatus);
        taskService.updateById(task);
        writeLog(task.getAgvId(), task.getId(), "状态变更",
                "任务「" + task.getTaskNo() + "」：" + logContent);
        pushTask(task);
    }

    /** 装卸货倒计时，返回 true 表示倒计时结束 */
    private boolean countDown(Long taskId) {
        int left = waitTicks.getOrDefault(taskId, 0) - 1;
        if (left <= 0) {
            waitTicks.remove(taskId);
            return true;
        }
        waitTicks.put(taskId, left);
        return false;
    }

    // ==================== 路径与电量模拟 ====================

    /**
     * AGV 向目标点移动（曼哈顿路径：先横后纵），返回是否已到达。
     * 速度单位为格/秒，每秒按速度累计可移动格数，每走 1 格耗电 1%。
     */
    private boolean moveToward(WmsAgvDevice agv, int tx, int ty) {
        if (agv.getPosX() == tx && agv.getPosY() == ty) {
            return true;
        }
        double speed = agv.getSpeed() == null ? 1.0 : agv.getSpeed().doubleValue();
        double acc = moveAcc.getOrDefault(agv.getId(), 0.0) + speed;
        boolean moved = false;
        while (acc >= 1.0 && !(agv.getPosX() == tx && agv.getPosY() == ty)) {
            if (agv.getPosX() < tx) {
                agv.setPosX(agv.getPosX() + 1);
            } else if (agv.getPosX() > tx) {
                agv.setPosX(agv.getPosX() - 1);
            } else if (agv.getPosY() < ty) {
                agv.setPosY(agv.getPosY() + 1);
            } else {
                agv.setPosY(agv.getPosY() - 1);
            }
            agv.setBattery(Math.max(0, agv.getBattery() - BATTERY_PER_CELL));
            acc -= 1.0;
            moved = true;
        }
        moveAcc.put(agv.getId(), acc);
        if (moved) {
            deviceService.updateById(agv);
            pushAgv(agv);
        }
        return agv.getPosX() == tx && agv.getPosY() == ty;
    }

    private int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    // ==================== 充电模拟 ====================

    /** 充电中的 AGV：每秒恢复电量，充满后回到空闲 */
    private void chargingTick() {
        List<WmsAgvDevice> charging = deviceService.lambdaQuery()
                .eq(WmsAgvDevice::getStatus, AGV_CHARGING).list();
        for (WmsAgvDevice agv : charging) {
            int battery = Math.min(100, agv.getBattery() + CHARGE_PER_TICK);
            agv.setBattery(battery);
            if (battery >= 100) {
                agv.setStatus(AGV_IDLE);
                writeLog(agv.getId(), null, "充电", agv.getAgvCode() + " 充电完成，电量恢复 100%");
            }
            deviceService.updateById(agv);
            pushAgv(agv);
        }
    }

    /** 空闲且低电量的 AGV：自动进入充电 */
    private void autoChargeTick() {
        List<WmsAgvDevice> lowBattery = deviceService.lambdaQuery()
                .eq(WmsAgvDevice::getEnabled, 1)
                .eq(WmsAgvDevice::getStatus, AGV_IDLE)
                .le(WmsAgvDevice::getBattery, AUTO_CHARGE_BATTERY)
                .list();
        for (WmsAgvDevice agv : lowBattery) {
            agv.setStatus(AGV_CHARGING);
            deviceService.updateById(agv);
            writeLog(agv.getId(), null, "充电", agv.getAgvCode() + " 电量不足（" + agv.getBattery() + "%），自动充电");
            pushAgv(agv);
        }
    }

    // ==================== 堵塞模拟 ====================

    private void blockTick() {
        blockTicks.replaceAll((id, left) -> left - 1);
        blockTicks.entrySet().removeIf(e -> {
            if (e.getValue() <= 0) {
                WmsAgvDevice agv = deviceService.getById(e.getKey());
                if (agv != null) {
                    writeLog(agv.getId(), null, "状态变更", agv.getAgvCode() + " 通道恢复通行");
                    pushAlert("info", agv.getAgvCode() + " 通道恢复通行");
                }
                return true;
            }
            return false;
        });
    }

    // ==================== 任务生成与取消（供业务钩子调用） ====================

    /** 生成搬运任务（入库上架/出库拣货/移库确认时由业务钩子调用） */
    public WmsAgvTask createTask(String taskType, String orderNo, Long materialId,
                                 Long fromLocationId, Long toLocationId, Integer priority) {
        WmsAgvTask task = new WmsAgvTask();
        task.setTaskNo(genTaskNo());
        task.setTaskType(taskType);
        task.setOrderNo(orderNo);
        task.setMaterialId(materialId);
        task.setFromLocationId(fromLocationId);
        task.setToLocationId(toLocationId);
        task.setPriority(priority == null ? 0 : priority);
        task.setStatus(TASK_PENDING);
        taskService.save(task);
        writeLog(null, task.getId(), "状态变更",
                "任务生成：「" + task.getTaskNo() + "」" + taskType + "，"
                        + (orderNo == null ? "手动创建" : "单据 " + orderNo));
        pushTask(task);
        return task;
    }

    /** 任务编号：AGVT + 日期 + 3位当日序号 */
    private String genTaskNo() {
        String prefix = "AGVT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = taskService.lambdaQuery().likeRight(WmsAgvTask::getTaskNo, prefix).count();
        return prefix + String.format("%03d", count + 1);
    }

    /** 取消单个任务：释放占用的 AGV */
    public void cancelTask(Long taskId, String reason) {
        WmsAgvTask task = taskService.getById(taskId);
        if (task == null || task.getStatus() >= TASK_DONE) {
            return;
        }
        freeAgv(task.getAgvId());
        task.setStatus(TASK_CANCELED);
        task.setErrorMsg(reason);
        taskService.updateById(task);
        writeLog(task.getAgvId(), task.getId(), "状态变更",
                "任务「" + task.getTaskNo() + "」已取消：" + reason);
        pushTask(task);
    }

    /** 按单据号取消全部未完成任务（单据作废时联动调用） */
    public void cancelByOrderNo(String orderNo, String reason) {
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .eq(WmsAgvTask::getOrderNo, orderNo)
                .lt(WmsAgvTask::getStatus, TASK_DONE)
                .list();
        for (WmsAgvTask task : tasks) {
            cancelTask(task.getId(), reason);
        }
    }

    /** 按类型+单据+物料取消（取消上架时联动调用） */
    public void cancelByTypeAndMaterial(String taskType, String orderNo, Long materialId, String reason) {
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .eq(WmsAgvTask::getTaskType, taskType)
                .eq(WmsAgvTask::getOrderNo, orderNo)
                .eq(WmsAgvTask::getMaterialId, materialId)
                .lt(WmsAgvTask::getStatus, TASK_DONE)
                .list();
        for (WmsAgvTask task : tasks) {
            cancelTask(task.getId(), reason);
        }
    }

    /** 释放 AGV 回空闲 */
    private void freeAgv(Long agvId) {
        if (agvId == null) {
            return;
        }
        WmsAgvDevice agv = deviceService.getById(agvId);
        if (agv != null && agv.getStatus() == AGV_RUNNING) {
            agv.setStatus(AGV_IDLE);
            deviceService.updateById(agv);
            pushAgv(agv);
        }
    }

    // ==================== 异常模拟与自动重分配 ====================

    /** 注入：故障停机。其执行中的任务自动回收为待分配，由其他空闲 AGV 接手 */
    public void injectFault(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        agv.setStatus(AGV_FAULT);
        deviceService.updateById(agv);
        moveAcc.remove(agvId);
        // 回收该 AGV 未完成的任务 → 重新进入待分配队列（紧急任务保持高优先级，自然插队）
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .eq(WmsAgvTask::getAgvId, agvId)
                .between(WmsAgvTask::getStatus, TASK_TO_PICK, TASK_RETURNING)
                .list();
        for (WmsAgvTask task : tasks) {
            task.setStatus(TASK_PENDING);
            task.setAgvId(null);
            task.setErrorMsg("AGV 故障停机，任务已回收等待重新分配");
            taskService.updateById(task);
            waitTicks.remove(task.getId());
            writeLog(agvId, task.getId(), "故障",
                    agv.getAgvCode() + " 故障停机，任务「" + task.getTaskNo() + "」已回收，等待改派");
            pushTask(task);
        }
        writeLog(agvId, null, "故障", agv.getAgvCode() + " 故障停机！");
        pushAlert("error", agv.getAgvCode() + " 故障停机！" + (tasks.isEmpty() ? "" : "已自动改派 " + tasks.size() + " 个任务"));
        pushAgv(agv);
    }

    /** 注入：电量不足（降到 8%，触发低电保护逻辑） */
    public void injectLowBattery(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        agv.setBattery(8);
        deviceService.updateById(agv);
        writeLog(agvId, null, "告警", agv.getAgvCode() + " 电量不足告警（8%）");
        pushAlert("warn", agv.getAgvCode() + " 电量不足，请及时充电");
        pushAgv(agv);
    }

    /** 注入：通道堵塞（暂停移动 10 秒） */
    public void injectBlock(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        blockTicks.put(agvId, 10);
        writeLog(agvId, null, "告警", agv.getAgvCode() + " 前方通道堵塞，暂停 10 秒");
        pushAlert("warn", agv.getAgvCode() + " 前方通道堵塞，暂停行驶");
    }

    /** 注入：取货失败（任务退回待分配，AGV 回空闲） */
    public void injectPickFail(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .eq(WmsAgvTask::getAgvId, agvId)
                .in(WmsAgvTask::getStatus, TASK_TO_PICK, TASK_PICKING)
                .list();
        if (tasks.isEmpty()) {
            throw new IllegalStateException("该 AGV 当前没有取货中的任务");
        }
        for (WmsAgvTask task : tasks) {
            task.setStatus(TASK_PENDING);
            task.setAgvId(null);
            task.setErrorMsg("取货失败，任务退回待分配");
            taskService.updateById(task);
            waitTicks.remove(task.getId());
            writeLog(agvId, task.getId(), "告警",
                    agv.getAgvCode() + " 取货失败，任务「" + task.getTaskNo() + "」退回待分配");
            pushTask(task);
        }
        agv.setStatus(AGV_IDLE);
        deviceService.updateById(agv);
        pushAlert("warn", agv.getAgvCode() + " 取货失败，任务已退回重派");
        pushAgv(agv);
    }

    /** 故障修复：故障 → 空闲 */
    public void repair(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        if (agv.getStatus() != AGV_FAULT) {
            throw new IllegalStateException("设备当前不在故障状态");
        }
        agv.setStatus(AGV_IDLE);
        deviceService.updateById(agv);
        writeLog(agvId, null, "状态变更", agv.getAgvCode() + " 故障已修复，恢复待命");
        pushAlert("info", agv.getAgvCode() + " 已修复，恢复运行");
        pushAgv(agv);
    }

    private WmsAgvDevice mustExist(Long agvId) {
        WmsAgvDevice agv = deviceService.getById(agvId);
        if (agv == null) {
            throw new IllegalStateException("AGV 设备不存在：" + agvId);
        }
        return agv;
    }

    // ==================== 坐标解析 ====================

    private void refreshLocationCache() {
        locationPosCache.clear();
        for (WmsLocation l : locationService.list()) {
            if (l.getPosX() != null && l.getPosY() != null) {
                locationPosCache.put(l.getId(), new int[]{l.getPosX(), l.getPosY()});
            }
        }
    }

    /** 解析库位坐标；库位为空或缺坐标时落到虚拟月台 (0,0) */
    private int[] resolvePos(Long locationId) {
        if (locationId != null) {
            int[] pos = locationPosCache.get(locationId);
            if (pos != null) {
                return pos;
            }
            WmsLocation l = locationService.getById(locationId);
            if (l != null && l.getPosX() != null && l.getPosY() != null) {
                return new int[]{l.getPosX(), l.getPosY()};
            }
        }
        return new int[]{0, 0};
    }

    // ==================== 日志与推送 ====================

    /** 写一条 AGV 日志（供 Controller 复用，如优先级调整） */
    public void writeTaskLog(WmsAgvTask task, String logType, String content) {
        writeLog(task.getAgvId(), task.getId(), logType, content);
        pushTask(task);
    }

    private void writeLog(Long agvId, Long taskId, String logType, String content) {
        WmsAgvLog log = new WmsAgvLog();
        log.setAgvId(agvId);
        log.setTaskId(taskId);
        log.setLogType(logType);
        log.setContent(content);
        logService.save(log);
    }

    private void pushAgv(WmsAgvDevice agv) {
        wsHandler.broadcast("agv", agv);
    }

    private void pushTask(WmsAgvTask task) {
        wsHandler.broadcast("task", task);
    }

    private void pushAlert(String level, String msg) {
        wsHandler.broadcast("alert", Map.of(
                "level", level,
                "msg", msg,
                "time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
    }
}
