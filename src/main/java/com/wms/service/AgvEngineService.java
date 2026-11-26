package com.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGV 调度引擎（v2.0 WCS 模拟核心，阶段 4 升级为多车协同）
 *
 * <p>用"每秒一次的定时器 + 状态机"模拟真实 WCS 的 AGV 硬件调度，无需任何真实设备：
 *  1. 任务分配：待分配任务按【紧急优先 → 创建时间】排序，分配给【空闲、电量充足、距离取货点最近】的 AGV
 *  2. 状态推进：待分配→前往取货→取货中→前往卸货→卸货中→返回待命区→已完成
 *  3. 路径规划：BFS 显式路径队列，逐 tick 续走；遇占用/封锁即时等待并重规划（阶段 4.1）
 *  4. 多车防撞：每 tick 重建"格 → AGV"占用表，进入下一格前校验，当前格保持占用形成双格预留（阶段 4.2）
 *  5. 死锁解除：等待图成环时按【紧急优先 → 距离近优先 → id 小优先】选出让行方，回退一格并重规划（阶段 4.3）
 *  6. 阻塞格：障碍按格子封锁 10 秒，其他车辆绕行，无法绕行则超时后重规划/回收（阶段 4.4）
 *  7. 状态机：8 态转换表集中校验，非法流转拒绝并记日志（阶段 4.5）
 *  8. 充电模拟：空闲低电量自动充电，充电中每秒恢复 10%
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

    /**
     * 8 态转换表（阶段 4.5）：key=当前状态，value=允许流转到的状态。
     * 正常流转沿 0→1→2→3→4→5→6；任意未完成状态可被回收（→0）或取消（→7）；6/7 为终态。
     */
    public static final Map<Integer, Set<Integer>> TASK_TRANSITIONS = Map.of(
            TASK_PENDING, Set.of(TASK_TO_PICK, TASK_CANCELED),
            TASK_TO_PICK, Set.of(TASK_PICKING, TASK_PENDING, TASK_CANCELED),
            TASK_PICKING, Set.of(TASK_TO_DROP, TASK_PENDING, TASK_CANCELED),
            TASK_TO_DROP, Set.of(TASK_DROPPING, TASK_PENDING, TASK_CANCELED),
            TASK_DROPPING, Set.of(TASK_RETURNING, TASK_PENDING, TASK_CANCELED),
            TASK_RETURNING, Set.of(TASK_DONE, TASK_PENDING, TASK_CANCELED),
            TASK_DONE, Set.of(),
            TASK_CANCELED, Set.of()
    );

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

    /** 阻塞格封锁时长（毫秒），阶段 4.4：格子级障碍 10 秒 */
    private static final long BLOCK_MS = 10_000L;
    /** 等待/阻塞超过该时长后重规划或回收任务（毫秒） */
    private static final long WAIT_REPLAN_MS = 10_000L;
    /** 单 tick 最多累计的移动格数上限 */
    private static final double MAX_ACC = 2.0;
    /** BFS 网格范围上限（防御异常坐标把搜索撑爆） */
    private static final int GRID_LIMIT = 30;
    /** 链式推挤的最大搜索深度（格），防止在拥挤网格里做无界搜索 */
    private static final int PUSH_MAX_DEPTH = 12;
    /** 任务回收后的冷却时长（毫秒），修 D4：同一任务不会立刻被派回同一台车 */
    private static final long RECYCLE_COOLDOWN_MS = 5_000L;

    private final WmsAgvDeviceService deviceService;
    private final WmsAgvTaskService taskService;
    private final WmsAgvLogService logService;
    private final WmsLocationService locationService;
    private final AgvWebSocketHandler wsHandler;

    /** 全局开关：false 时引擎休眠、不生成任务，系统表现与 v1.0 一致 */
    @Value("${agv.enabled:true}")
    private boolean enabled;

    /**
     * 分配策略（阶段 5.3 对比实验）：
     * <ul>
     *   <li>{@code nearest}：就近分配——取到取货点曼哈顿距离最小的空闲车</li>
     *   <li>{@code nearest-load}：就近 + 负载惩罚——在距离分上叠加该车已完成任务数的惩罚项，
     *       让任务在整个车队里摊开，避免总是同一台车就近接单</li>
     * </ul>
     * 论文第 5 章的对比实验正是靠这个开关保证"组间只有策略变量不同"。
     */
    @Value("${agv.strategy:nearest}")
    private String strategyCode = "nearest";

    /** 负载惩罚系数：每多完成一单，评分增加该值（单位：格） */
    private static final int LOAD_PENALTY_WEIGHT = 6;
    /** 策略编码：就近分配 */
    public static final String STRATEGY_NEAREST = "nearest";
    /** 策略编码：就近 + 负载惩罚 */
    public static final String STRATEGY_NEAREST_LOAD = "nearest-load";

    /** 移动累计器：AGV-ID → 已累计的可移动格数（支持 0.8 格/秒这类小速度） */
    private final Map<Long, Double> moveAcc = new ConcurrentHashMap<>();
    /** 装卸货倒计时：任务ID → 剩余秒数 */
    private final Map<Long, Integer> waitTicks = new ConcurrentHashMap<>();
    /** 库位坐标缓存（库位ID → [x, y]），每秒重建一次，数据量小 */
    private final Map<Long, int[]> locationPosCache = new ConcurrentHashMap<>();

    /** 路径队列：AGV-ID → 剩余路径格子（不含当前格），跨 tick 续走（阶段 4.1） */
    private final Map<Long, List<int[]>> pathQueue = new ConcurrentHashMap<>();
    /** 路径所属任务阶段：AGV-ID → 任务状态，阶段变化即重算路径 */
    private final Map<Long, Integer> pathPhase = new ConcurrentHashMap<>();
    /** 路径目标格：AGV-ID → "x,y" */
    private final Map<Long, String> pathTarget = new ConcurrentHashMap<>();

    /** 实时格子占用表：x,y → AGV-ID，每 tick 由设备表重建（阶段 4.2） */
    private final Map<String, Long> cellOwner = new ConcurrentHashMap<>();
    /** 阻塞格：x,y → 解封时间戳（毫秒）（阶段 4.4） */
    private final Map<String, Long> blockedCells = new ConcurrentHashMap<>();
    /** 等待起始时刻：AGV-ID → 毫秒时间戳，用于 10 秒超时判定（阶段 4.3/4.4） */
    private final Map<Long, Long> waitingSince = new ConcurrentHashMap<>();
    /** 等待原因：AGV-ID → 文案，随 WS 下发给地图 */
    private final Map<Long, String> waitingReason = new ConcurrentHashMap<>();
    /** 本 tick 处于等待的 AGV，用于清理已恢复的等待标识 */
    private final Set<Long> waitingThisTick = ConcurrentHashMap.newKeySet();
    /** 等待升级阶段：AGV-ID → 已升级次数（1=已强制换路，2=已回收任务），修 D1 等待无超时 */
    private final Map<Long, Integer> waitStage = new ConcurrentHashMap<>();
    /** 任务回收冷却：taskId → 冷却截止时间戳，避免同一任务被立刻派回同一台车形成活锁（修 D4） */
    private final Map<Long, Long> recycleCooldown = new ConcurrentHashMap<>();
    /** 任务回收避让：taskId → 刚出过问题的 AGV-ID，派单时优先跳过它（修 D4） */
    private final Map<Long, Long> recycleAvoidAgv = new ConcurrentHashMap<>();

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
            expireBlocks();
            waitingThisTick.clear();
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
        // 各车累计完成单数：负载惩罚策略要用（阶段 5.3）
        Map<Long, Long> loadMap = completedLoadMap();
        for (WmsAgvTask task : pending) {
            if (idleAgvs.isEmpty()) {
                return;
            }
            // 修 D4：刚被回收的任务还在冷却期，先不派，避免"回收→原车接单→原地死锁"活锁
            if (isInRecycleCooldown(task.getId())) {
                continue;
            }
            int[] from = resolvePos(task.getFromLocationId());
            // 距离取货点曼哈顿距离最近的 AGV 优先；近+负载惩罚策略额外叠加负载分（阶段 5.3）
            // 刚在这单上出过问题的车尽量避开（修 D4）
            Long avoidId = recycleAvoidAgv.get(task.getId());
            WmsAgvDevice best = idleAgvs.stream()
                    .filter(a -> !a.getId().equals(avoidId))
                    .min(Comparator.comparingInt(a -> assignScore(a, from, loadMap)))
                    .orElse(null);
            if (best == null) {
                // 只有那台"出过问题"的车可选时也允许派它，避免任务饿死
                best = idleAgvs.stream()
                        .min(Comparator.comparingInt(a -> assignScore(a, from, loadMap)))
                        .orElse(null);
            }
            if (best == null) {
                return;
            }
            assertTransition(task, TASK_TO_PICK);
            task.setAgvId(best.getId());
            task.setStatus(TASK_TO_PICK);
            task.setStartTime(LocalDateTime.now());
            taskService.updateById(task);
            // 该任务已重新派发：清掉冷却与避让记录
            recycleCooldown.remove(task.getId());
            recycleAvoidAgv.remove(task.getId());
            best.setStatus(AGV_RUNNING);
            deviceService.updateById(best);
            idleAgvs.remove(best);
            clearPath(best.getId());
            writeLog(best.getId(), task.getId(), "状态变更",
                    "任务「" + task.getTaskNo() + "」分配给 " + best.getAgvCode() + "，前往取货");
            pushTask(task);
            pushAgv(best);
        }
    }

    // ==================== 状态机推进 ====================

    /**
     * 推进所有执行中的任务。任务按【优先级降序 → 任务 id 升序】处理，
     * 高优先级先占格，天然体现"紧急优先"；处理完统一做死锁检测。
     */
    private void progressRunningTasks() {
        List<WmsAgvDevice> devices = deviceService.list();
        Map<Long, WmsAgvDevice> agvById = new HashMap<>();
        for (WmsAgvDevice d : devices) {
            agvById.put(d.getId(), d);
        }
        rebuildOccupancy(devices);

        List<WmsAgvTask> running = taskService.lambdaQuery()
                .between(WmsAgvTask::getStatus, TASK_TO_PICK, TASK_RETURNING)
                .isNotNull(WmsAgvTask::getAgvId)
                .list();
        running.sort(Comparator
                .comparingInt((WmsAgvTask t) -> t.getPriority() == null ? 0 : t.getPriority())
                .reversed()
                .thenComparing(WmsAgvTask::getId));

        Map<Long, Long> waitGraph = new HashMap<>();
        Map<Long, WmsAgvTask> taskByAgv = new HashMap<>();
        for (WmsAgvTask task : running) {
            taskByAgv.put(task.getAgvId(), task);
        }
        for (WmsAgvTask task : running) {
            WmsAgvDevice agv = agvById.get(task.getAgvId());
            advanceTask(task, agv, waitGraph);
        }
        // 已经不再等待的 AGV：清掉等待标识并通知前端
        sweepWaiting();
        // 等待图成环 → 打破死锁（阶段 4.3）
        detectAndBreakDeadlock(waitGraph, agvById, taskByAgv);
    }

    /** 推进一步单个任务 */
    private void advanceTask(WmsAgvTask task, WmsAgvDevice agv, Map<Long, Long> waitGraph) {
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
                int[] from = resolvePos(task.getFromLocationId());
                int moved = moveToward(agv, task, from[0], from[1], waitGraph);
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
                int[] to = resolvePos(task.getToLocationId());
                int moved = moveToward(agv, task, to[0], to[1], waitGraph);
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
                int moved = moveToward(agv, task, agv.getHomeX(), agv.getHomeY(), waitGraph);
                addDistance(task, moved, false);
                if (agv.getPosX() == agv.getHomeX() && agv.getPosY() == agv.getHomeY()) {
                    task.setFinishTime(LocalDateTime.now());
                    changeTaskStatus(task, TASK_DONE, "执行完成，" + agv.getAgvCode() + " 回到待命区");
                    clearPath(agv.getId());
                    agv.setStatus(AGV_IDLE);
                    deviceService.updateById(agv);
                    clearWaiting(agv.getId());
                    pushAgv(agv);
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
     * 空驶率 = 空驶里程 ÷ 总里程，用于论文第 5 章的对比实验指标。
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

    /** 任务状态变更 + 转换校验 + 日志 + 推送 */
    private void changeTaskStatus(WmsAgvTask task, int newStatus, String logContent) {
        assertTransition(task, newStatus);
        task.setStatus(newStatus);
        taskService.updateById(task);
        writeLog(task.getAgvId(), task.getId(), "状态变更",
                "任务「" + task.getTaskNo() + "」：" + logContent);
        pushTask(task);
    }

    /** 状态码中文名 */
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

    /** 状态转换表校验：非法流转抛异常并留日志（阶段 4.5） */
    public static boolean canTransition(int from, int to) {
        return TASK_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 断言流转合法，非法则记录"状态变更"日志并抛出明确的 IllegalStateException */
    private void assertTransition(WmsAgvTask task, int target) {
        int from = task.getStatus();
        if (!canTransition(from, target)) {
            String msg = "非法状态流转：" + statusName(from) + "(" + from + ")"
                    + " → " + statusName(target) + "(" + target + ")";
            writeLog(task.getAgvId(), task.getId(), "状态变更",
                    "任务「" + task.getTaskNo() + "」" + msg + "，已拒绝");
            throw new IllegalStateException(msg);
        }
    }

    /** 8 态转换表（供接口/验收脚本读取） */
    public List<Map<String, Object>> transitionTable() {
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

    /** 管理员手动流转（同时作为 4.5 的校验入口：非法流转返回明确错误） */
    public WmsAgvTask forceTransition(Long taskId, int target) {
        WmsAgvTask task = taskService.getById(taskId);
        if (task == null) {
            throw new IllegalStateException("任务不存在：" + taskId);
        }
        if (target < TASK_PENDING || target > TASK_CANCELED) {
            throw new IllegalStateException("状态码超出范围（0-7）：" + target);
        }
        if (!canTransition(task.getStatus(), target)) {
            String msg = "非法状态流转：" + statusName(task.getStatus()) + "(" + task.getStatus()
                    + ") → " + statusName(target) + "(" + target + ")";
            writeLog(task.getAgvId(), task.getId(), "状态变更",
                    "任务「" + task.getTaskNo() + "」" + msg + "，已拒绝");
            throw new IllegalStateException(msg);
        }
        changeTaskStatus(task, target, "管理员手动流转至「" + statusName(target) + "」");
        return task;
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

    // ==================== 路径规划与多车防撞（阶段 4.1 / 4.2） ====================

    /**
     * 沿显式路径队列向目标点移动，返回是否已到达。
     * <ul>
     *   <li>进入下一格前检查阻塞格与占用表；占用则等待，本 tick 不再前进；</li>
     *   <li>当前格在移动完成前保持占用（双格预留），避免两车同 tick 抢同一格；</li>
     *   <li>速度单位为格/秒，每走 1 格耗电 1%；单 tick 最多走 2 格。</li>
     * </ul>
     */
    private int moveToward(WmsAgvDevice agv, WmsAgvTask task, int tx, int ty,
                           Map<Long, Long> waitGraph) {
        if (agv.getPosX() == tx && agv.getPosY() == ty) {
            clearWaiting(agv.getId());
            return 0;
        }
        // 电量归零禁行：不再移动，等待回收/充电（阶段 3.2）
        if (agv.getBattery() == null || agv.getBattery() <= 0) {
            return 0;
        }
        double speed = agv.getSpeed() == null ? 1.0 : agv.getSpeed().doubleValue();
        double acc = Math.min(MAX_ACC, moveAcc.getOrDefault(agv.getId(), 0.0) + speed);
        boolean moved = false;
        int movedCells = 0;
        while (acc >= 1.0 && !(agv.getPosX() == tx && agv.getPosY() == ty)) {
            int[] next = nextStep(agv, task, tx, ty);
            if (next == null) {
                // 目标被封锁、暂时不可达：等待通道恢复，10 秒仍不可达则回收任务
                long waited = markWaiting(agv, task, null, "目标暂时不可达，等待通道恢复");
                escalateWait(agv, task, waited, "目标暂时不可达且无法绕行");
                break;
            }
            String key = cellKey(next[0], next[1]);
            if (isBlocked(key)) {
                long waited = markWaiting(agv, task, null, "路径前方格子(" + key + ")封锁，等待绕行");
                escalateWait(agv, task, waited, "前方格子(" + key + ")封锁且无法绕行");
                break;
            }
            Long owner = cellOwner.get(key);
            if (owner != null && !owner.equals(agv.getId())) {
                // 挡路的是空闲车：先请它让位（链式推挤，可跨多跳），避免被闲置车辆堵死（修 D2/D3）
                if (isIdleAgv(owner) && tryYieldIdle(owner, agv.getId(), key)) {
                    continue;
                }
                // 双格预留：对方仍占用目标格，本车等待；记录等待图供死锁检测
                long waited = markWaiting(agv, task, owner, "等待 " + agvCode(owner) + " 让出格子(" + key + ")");
                waitGraph.put(agv.getId(), owner);
                // 等待超时升级：10 秒强制换路，20 秒回收任务（修 D1：原先此分支无超时，可无限等待）
                escalateWait(agv, task, waited,
                        "格子(" + key + ")一直被 " + agvCode(owner) + " 占用");
                break;
            }
            // 通过校验：先释放当前格，再占用目标格（同一 tick 内完成，等价双格预留）
            cellOwner.remove(cellKey(agv.getPosX(), agv.getPosY()));
            agv.setPosX(next[0]);
            agv.setPosY(next[1]);
            cellOwner.put(key, agv.getId());
            agv.setBattery(Math.max(0, agv.getBattery() - BATTERY_PER_CELL));
            acc -= 1.0;
            moved = true;
            movedCells++;
            removePathHead(agv.getId(), next);
            clearWaiting(agv.getId());
            if (agv.getBattery() != null && agv.getBattery() <= 0) {
                break;
            }
        }
        // 被阻塞时余量封顶 1 格，避免解除阻塞瞬间连跳
        moveAcc.put(agv.getId(), Math.min(acc, MAX_ACC));
        if (moved) {
            deviceService.updateById(agv);
            pushAgv(agv);
        }
        return movedCells;
    }

    /**
     * 取下一步：路径队列为空/阶段变化/目标变化/偏航时重算 BFS 路径。
     */
    private int[] nextStep(WmsAgvDevice agv, WmsAgvTask task, int tx, int ty) {
        Long agvId = agv.getId();
        String targetKey = tx + "," + ty;
        List<int[]> path = pathQueue.get(agvId);
        boolean stale = path == null || path.isEmpty()
                || !Integer.valueOf(task.getStatus()).equals(pathPhase.get(agvId))
                || !targetKey.equals(pathTarget.get(agvId))
                || !isAdjacent(agv.getPosX(), agv.getPosY(), path.get(0));
        if (stale) {
            path = findPath(agv.getPosX(), agv.getPosY(), tx, ty);
            if (path == null) {
                pathQueue.remove(agvId);
                return null;
            }
            pathQueue.put(agvId, path);
            pathPhase.put(agvId, task.getStatus());
            pathTarget.put(agvId, targetKey);
        }
        return path.isEmpty() ? null : path.get(0);
    }

    /** 路径走完一格：移除队头（防御性判断，避免并发/重算后错位） */
    private void removePathHead(Long agvId, int[] cell) {
        List<int[]> path = pathQueue.get(agvId);
        if (path != null && !path.isEmpty()
                && path.get(0)[0] == cell[0] && path.get(0)[1] == cell[1]) {
            path.remove(0);
        }
    }

    /**
     * BFS 寻路（4 邻接）。先避开阻塞格 + 动态占用；无解时忽略动态占用再试一次，
     * 让车辆能靠近等待而不是直接判定不可达。
     */
    private List<int[]> findPath(int sx, int sy, int tx, int ty) {
        List<int[]> path = bfs(sx, sy, tx, ty, true);
        if (path == null) {
            path = bfs(sx, sy, tx, ty, false);
        }
        return path;
    }

    private List<int[]> bfs(int sx, int sy, int tx, int ty, boolean avoidOccupied) {
        if (sx == tx && sy == ty) {
            return new ArrayList<>();
        }
        int maxX = Math.min(GRID_LIMIT, Math.max(Math.max(gridMaxX(), tx), sx));
        int maxY = Math.min(GRID_LIMIT, Math.max(Math.max(gridMaxY(), ty), sy));
        String startKey = cellKey(sx, sy);
        String goalKey = cellKey(tx, ty);
        Map<String, int[]> parent = new HashMap<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy});
        seen.add(startKey);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx > maxX || ny > maxY) {
                    continue;
                }
                String key = cellKey(nx, ny);
                if (seen.contains(key)) {
                    continue;
                }
                // 阻塞格永远不可通行（阶段 4.4）
                if (isBlocked(key)) {
                    continue;
                }
                // 动态占用：终点格允许进入（进入前还会再校验一次），其余直接绕开
                if (avoidOccupied && !key.equals(goalKey) && cellOwner.containsKey(key)) {
                    continue;
                }
                parent.put(key, cur);
                if (key.equals(goalKey)) {
                    return buildPath(parent, sx, sy, tx, ty);
                }
                seen.add(key);
                queue.add(new int[]{nx, ny});
            }
        }
        return null;
    }

    /** 由 parent 链回溯出路径（不含起点，含终点） */
    private List<int[]> buildPath(Map<String, int[]> parent, int sx, int sy, int tx, int ty) {
        List<int[]> cells = new ArrayList<>();
        int cx = tx;
        int cy = ty;
        while (!(cx == sx && cy == sy)) {
            cells.add(new int[]{cx, cy});
            int[] p = parent.get(cellKey(cx, cy));
            if (p == null) {
                return null;
            }
            cx = p[0];
            cy = p[1];
        }
        java.util.Collections.reverse(cells);
        return cells;
    }

    /** 每 tick 由设备表重建占用表：同格只保留一台（异常数据也不至于让地图崩） */
    private void rebuildOccupancy(List<WmsAgvDevice> devices) {
        cellOwner.clear();
        for (WmsAgvDevice d : devices) {
            if (d.getPosX() != null && d.getPosY() != null) {
                cellOwner.put(cellKey(d.getPosX(), d.getPosY()), d.getId());
            }
        }
    }

    private int gridMaxX() {
        int max = 0;
        for (int[] p : locationPosCache.values()) {
            max = Math.max(max, p[0]);
        }
        for (WmsAgvDevice d : deviceService.list()) {
            if (d.getPosX() != null) {
                max = Math.max(max, d.getPosX());
            }
            if (d.getHomeX() != null) {
                max = Math.max(max, d.getHomeX());
            }
        }
        return max;
    }

    private int gridMaxY() {
        int max = 0;
        for (int[] p : locationPosCache.values()) {
            max = Math.max(max, p[1]);
        }
        for (WmsAgvDevice d : deviceService.list()) {
            if (d.getPosY() != null) {
                max = Math.max(max, d.getPosY());
            }
            if (d.getHomeY() != null) {
                max = Math.max(max, d.getHomeY());
            }
        }
        return max;
    }

    // ==================== 等待标识（阶段 4.6） ====================

    /**
     * 记录等待并推送一次 WS waiting 事件；返回已等待毫秒数（首次调用返回 0）。
     */
    private long markWaiting(WmsAgvDevice agv, WmsAgvTask task, Long blockerId, String reason) {
        long now = System.currentTimeMillis();
        Long since = waitingSince.putIfAbsent(agv.getId(), now);
        waitingThisTick.add(agv.getId());
        waitingReason.put(agv.getId(), reason);
        if (since == null) {
            since = now;
            writeLog(agv.getId(), task == null ? null : task.getId(), "冲突", agv.getAgvCode() + " " + reason);
            wsHandler.broadcast("waiting", Map.of(
                    "agvId", agv.getId(),
                    "waiting", true,
                    "since", now,
                    "reason", reason));
        }
        return now - since;
    }

    /** 解除等待并通知前端（等待标识消失） */
    private void clearWaiting(Long agvId) {
        if (waitingSince.remove(agvId) != null) {
            waitingReason.remove(agvId);
            waitStage.remove(agvId);
            wsHandler.broadcast("waiting", Map.of("agvId", agvId, "waiting", false));
        }
    }

    /**
     * 等待超时升级（修 D1）。
     *
     * <p>原先只有"目标不可达""前方封锁"两条分支有超时，而"格子被别人占用"这条最常见的分支
     * 没有任何超时，导致任务可以无限期停在"前往取货/返回待命区"。这里统一成三级升级：
     * <ol>
     *   <li>等待 &lt; 10 秒：继续等（对方可能马上挪开）</li>
     *   <li>等待 ≥ 10 秒：清空路径，下一 tick 重新 BFS 绕行（记一次升级）</li>
     *   <li>再等 ≥ 10 秒（共 20 秒）：回收任务退回待分配，避免任务卡死</li>
     * </ol>
     */
    private void escalateWait(WmsAgvDevice agv, WmsAgvTask task, long waited, String reason) {
        if (waited < WAIT_REPLAN_MS) {
            return;
        }
        int stage = waitStage.merge(agv.getId(), 1, Integer::sum);
        if (stage <= 1) {
            // 第一级：强制换路。清空路径后下一 tick 重新 BFS，绕开占用格
            clearPath(agv.getId());
            writeLog(agv.getId(), task == null ? null : task.getId(), "冲突",
                    agv.getAgvCode() + " " + reason + " 超过 " + (WAIT_REPLAN_MS / 1000)
                            + " 秒，重新规划路径绕行");
            pushAlert("warn", agv.getAgvCode() + " 等待超时，重新规划路径");
        } else {
            // 第二级：回收任务，任务回到待分配队列，优先级保留
            writeLog(agv.getId(), task == null ? null : task.getId(), "冲突",
                    agv.getAgvCode() + " " + reason + " 持续超过 " + (2 * WAIT_REPLAN_MS / 1000)
                            + " 秒，回收任务退回待分配");
            if (task != null) {
                recycleTask(task, agv, reason + " 等待超时，任务退回待分配");
            } else {
                clearWaiting(agv.getId());
            }
        }
    }

    /** 该 AGV 当前是否处于空闲（可被临时推挤让位） */
    private boolean isIdleAgv(Long agvId) {
        WmsAgvDevice agv = deviceService.getById(agvId);
        return agv != null && agv.getStatus() == AGV_IDLE;
    }

    /** 清理已经不再等待的 AGV（如故障/任务结束），避免地图上残留"等待中" */
    private void sweepWaiting() {
        for (Long id : new ArrayList<>(waitingSince.keySet())) {
            if (!waitingThisTick.contains(id)) {
                clearWaiting(id);
            }
        }
    }

    // ==================== 死锁检测与打破（阶段 4.3） ====================

    /**
     * 等待图（AGV → 阻挡它的 AGV）成环即判定死锁：
     * 按【紧急优先 → 距离目标近优先 → id 小优先】选出赢家，环上其余一方强制让行：
     * 侧移一格空闲格并清空路径重规划；无空闲格则回收任务退回待分配。
     */
    private void detectAndBreakDeadlock(Map<Long, Long> waitGraph,
                                        Map<Long, WmsAgvDevice> agvById,
                                        Map<Long, WmsAgvTask> taskByAgv) {
        if (waitGraph.size() < 2) {
            return;
        }
        List<Long> cycle = findCycle(waitGraph);
        if (cycle.isEmpty()) {
            return;
        }
        Long victim = chooseVictim(cycle, agvById, taskByAgv);
        WmsAgvDevice v = agvById.get(victim);
        if (v == null) {
            return;
        }
        WmsAgvTask task = taskByAgv.get(victim);
        // 让行方回退到一处空格：优先用链式推挤（可跨多跳），找不到再退回 1 邻格（修 D2）
        List<int[]> chain = findPushChain(v, null);
        if (chain != null && !chain.isEmpty()) {
            applyPushChain(chain, v, victim, cellKey(v.getPosX(), v.getPosY()));
            writeLog(victim, task == null ? null : task.getId(), "冲突",
                    "检测到死锁等待环 " + cycle + "，" + v.getAgvCode() + " 按规则让行并重新规划路径");
            pushAlert("warn", "死锁解除：" + v.getAgvCode() + " 让行");
        } else {
            int[] side = findFreeSideCell(v, null);
            if (side != null) {
                cellOwner.remove(cellKey(v.getPosX(), v.getPosY()));
                v.setPosX(side[0]);
                v.setPosY(side[1]);
                cellOwner.put(cellKey(side[0], side[1]), v.getId());
                v.setBattery(Math.max(0, (v.getBattery() == null ? 100 : v.getBattery()) - BATTERY_PER_CELL));
                deviceService.updateById(v);
                pushAgv(v);
                clearPath(victim);
                writeLog(victim, task == null ? null : task.getId(), "冲突",
                        "检测到死锁等待环 " + cycle + "，" + v.getAgvCode() + " 按规则让行，回退到 ("
                                + side[0] + "," + side[1] + ") 并重新规划路径");
                pushAlert("warn", "死锁解除：" + v.getAgvCode() + " 让行");
            } else if (task != null) {
                writeLog(victim, task.getId(), "冲突",
                        "检测到死锁等待环 " + cycle + "，" + v.getAgvCode() + " 无空闲格可让行，回收任务");
                recycleTask(task, v, "死锁且无空闲格让行，任务退回待分配");
            }
        }
        clearWaiting(victim);
        waitingThisTick.remove(victim);
    }

    /**
     * 沿等待图找环，返回环上节点（有序），无环返回空列表。
     *
     * <p><b>修 D3</b>：空闲车没有出边，等待图里走不到它自己，不构成有向环，
     * 于是"被闲置车挡住"这种最常见的堵车就检测不出来。这里额外判定：
     * 若某辆<b>执行中</b>的车在等一辆<b>空闲</b>车，而那辆空闲车本身没有等待对象，
     * 就把它当作"被闲置车阻塞"直接加入环，让死锁解除逻辑去处理。
     */
    private List<Long> findCycle(Map<Long, Long> waitGraph) {
        Set<Long> globalSeen = new HashSet<>();
        for (Long start : waitGraph.keySet()) {
            if (globalSeen.contains(start)) {
                continue;
            }
            Map<Long, Integer> idx = new LinkedHashMap<>();
            Long cur = start;
            while (cur != null && !idx.containsKey(cur) && !globalSeen.contains(cur)) {
                idx.put(cur, idx.size());
                cur = waitGraph.get(cur);
            }
            if (cur != null && idx.containsKey(cur)) {
                List<Long> nodes = new ArrayList<>(idx.keySet());
                return new ArrayList<>(nodes.subList(idx.get(cur), nodes.size()));
            }
            globalSeen.addAll(idx.keySet());
        }
        // 无有向环：查"被闲置车阻塞"（修 D3）
        for (Map.Entry<Long, Long> e : waitGraph.entrySet()) {
            Long waiter = e.getKey();
            Long blocker = e.getValue();
            if (blocker == null || blocker.equals(waiter)) {
                continue;
            }
            // 挡路车本身没有等待对象，且是空闲车 → 典型的"被闲置车堵死"
            if (!waitGraph.containsKey(blocker) && isIdleAgv(blocker)) {
                return List.of(waiter, blocker);
            }
        }
        return List.of();
    }

    /** 选出让行方：priority 低、离目标远、id 大的排在后面 */
    private Long chooseVictim(List<Long> cycle, Map<Long, WmsAgvDevice> agvById,
                              Map<Long, WmsAgvTask> taskByAgv) {
        List<Long> ranked = new ArrayList<>(cycle);
        ranked.sort(Comparator
                .comparingInt((Long id) -> {
                    WmsAgvTask t = taskByAgv.get(id);
                    return t == null || t.getPriority() == null ? 0 : t.getPriority();
                }).reversed()
                .thenComparingInt(id -> distanceToTarget(id, agvById, taskByAgv))
                .thenComparingLong(id -> id));
        return ranked.get(ranked.size() - 1);
    }

    /** 距离其当前阶段目标点的曼哈顿距离（越小越"更该先走"） */
    private int distanceToTarget(Long agvId, Map<Long, WmsAgvDevice> agvById,
                                 Map<Long, WmsAgvTask> taskByAgv) {
        WmsAgvDevice agv = agvById.get(agvId);
        WmsAgvTask task = taskByAgv.get(agvId);
        if (agv == null || task == null) {
            return Integer.MAX_VALUE / 2;
        }
        int[] target = switch (task.getStatus()) {
            case TASK_TO_PICK, TASK_PICKING -> resolvePos(task.getFromLocationId());
            case TASK_TO_DROP, TASK_DROPPING -> resolvePos(task.getToLocationId());
            default -> new int[]{agv.getHomeX() == null ? 0 : agv.getHomeX(),
                    agv.getHomeY() == null ? 0 : agv.getHomeY()};
        };
        return manhattan(agv.getPosX(), agv.getPosY(), target[0], target[1]);
    }

    /** 找一个可侧移的空闲格：不越界、非阻塞、无人占用 */
    private int[] findFreeSideCell(WmsAgvDevice agv, String excludeKey) {
        int maxX = Math.min(GRID_LIMIT, Math.max(gridMaxX(), agv.getPosX()));
        int maxY = Math.min(GRID_LIMIT, Math.max(gridMaxY(), agv.getPosY()));
        int[][] dirs = {{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
        for (int[] d : dirs) {
            int nx = agv.getPosX() + d[0];
            int ny = agv.getPosY() + d[1];
            if (nx < 0 || ny < 0 || nx > maxX || ny > maxY) {
                continue;
            }
            String key = cellKey(nx, ny);
            if ((excludeKey != null && excludeKey.equals(key)) || isBlocked(key) || cellOwner.containsKey(key)) {
                continue;
            }
            return new int[]{nx, ny};
        }
        return null;
    }

    /**
     * 请挡路的空闲 AGV 临时让位（阶段 4.2 补充：闲置车辆不应把通道/工位锁死）。
     *
     * <p><b>链式推挤（修 D2）</b>：原先只在 4 邻格内找一个空格，四邻全被占就直接放弃，
     * 于是几台空闲车会互相围死、任务无限等待。现在从挡路车出发做 BFS：
     * 允许"穿过"其他空闲车（把它们记进推挤链），找到最近的真正空位后，
     * 从链尾往前依次挪车——先挪最深的一台，再挪它前面那台，腾出通路。
     *
     * <p>只有 IDLE 状态的车可以被推挤；充电中、故障或有在途任务的车不动。
     *
     * @return 是否成功让位（让位后请求方即可继续前进）
     */
    private boolean tryYieldIdle(Long ownerId, Long requesterId, String blockedKey) {
        WmsAgvDevice owner = deviceService.getById(ownerId);
        if (owner == null || owner.getStatus() != AGV_IDLE) {
            return false;
        }
        // 目标空位不能是请求方正在等的那一格
        List<int[]> chain = findPushChain(owner, blockedKey);
        if (chain == null || chain.isEmpty()) {
            return false;
        }
        return applyPushChain(chain, owner, requesterId, blockedKey);
    }

    /**
     * 从 {@code owner} 出发找一条"推挤链"：chain[0] 是 owner 所在格，
     * chain[last] 是一个真正空闲的格子，中间经过的格子可以由其他空闲车占据。
     * 找不到返回 null。
     */
    private List<int[]> findPushChain(WmsAgvDevice owner, String excludeKey) {
        int maxX = Math.min(GRID_LIMIT, Math.max(gridMaxX(), owner.getPosX()));
        int maxY = Math.min(GRID_LIMIT, Math.max(gridMaxY(), owner.getPosY()));
        String startKey = cellKey(owner.getPosX(), owner.getPosY());
        Map<String, int[]> parent = new HashMap<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{owner.getPosX(), owner.getPosY()});
        seen.add(startKey);
        int[][] dirs = {{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
        int depth = 0;
        while (!queue.isEmpty() && depth < PUSH_MAX_DEPTH) {
            depth++;
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                int[] cur = queue.poll();
                for (int[] d : dirs) {
                    int nx = cur[0] + d[0];
                    int ny = cur[1] + d[1];
                    if (nx < 0 || ny < 0 || nx > maxX || ny > maxY) {
                        continue;
                    }
                    String key = cellKey(nx, ny);
                    if (seen.contains(key) || isBlocked(key)
                            || (excludeKey != null && excludeKey.equals(key))) {
                        continue;
                    }
                    // 这一格是否被车占着？只有空闲车可以被推
                    Long holder = cellOwner.get(key);
                    if (holder != null && !isIdleAgv(holder)) {
                        continue;
                    }
                    parent.put(key, cur);
                    if (holder == null) {
                        // 找到真正空位：回溯成链
                        return buildPushChain(parent, startKey, key);
                    }
                    seen.add(key);
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return null;
    }

    /** 由 parent 链回溯出推挤链（不含起点，含终点空位） */
    private List<int[]> buildPushChain(Map<String, int[]> parent, String startKey, String endKey) {
        List<int[]> chain = new ArrayList<>();
        String cur = endKey;
        int guard = 0;
        while (!cur.equals(startKey) && guard++ < PUSH_MAX_DEPTH + 2) {
            String[] xy = cur.split(",");
            chain.add(new int[]{Integer.parseInt(xy[0]), Integer.parseInt(xy[1])});
            int[] p = parent.get(cur);
            if (p == null) {
                return null;
            }
            cur = cellKey(p[0], p[1]);
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    /**
     * 执行链式推挤：从链尾（真正的空位）往前依次挪车。
     * 链尾先动，前面的车再跟进它刚腾出来的格子。
     */
    private boolean applyPushChain(List<int[]> chain, WmsAgvDevice owner,
                                   Long requesterId, String blockedKey) {
        // 先收集每台车的新位置，并校验所有车都还可推（中途失败会留下半推状态）
        List<long[]> moves = new ArrayList<>();
        for (int[] from : chain) {
            Long holder = cellOwner.get(cellKey(from[0], from[1]));
            if (holder != null) {
                WmsAgvDevice mover = deviceService.getById(holder);
                if (mover == null || mover.getStatus() != AGV_IDLE) {
                    return false;
                }
                moves.add(new long[]{holder, from[0], from[1]});
            }
        }
        if (moves.isEmpty()) {
            return false;
        }
        // 从链尾往前推：链尾那台车先挪进空格，其余依次跟进
        for (int i = moves.size() - 1; i >= 0; i--) {
            long[] mv = moves.get(i);
            WmsAgvDevice mover = deviceService.getById(mv[0]);
            if (mover == null) {
                continue;
            }
            int fromX = mover.getPosX();
            int fromY = mover.getPosY();
            cellOwner.remove(cellKey(fromX, fromY));
            mover.setPosX((int) mv[1]);
            mover.setPosY((int) mv[2]);
            cellOwner.put(cellKey(mover.getPosX(), mover.getPosY()), mover.getId());
            mover.setBattery(Math.max(0, (mover.getBattery() == null ? 100 : mover.getBattery())
                    - BATTERY_PER_CELL));
            deviceService.updateById(mover);
            pushAgv(mover);
            writeLog(mover.getId(), null, "冲突", mover.getAgvCode() + " 被临时推挤让位：("
                    + fromX + "," + fromY + ") → (" + mover.getPosX() + "," + mover.getPosY()
                    + ")，为 " + agvCode(requesterId) + " 放行格子 (" + blockedKey + ")");
        }
        // owner 的路径作废：它挪过位置了
        clearPath(owner.getId());
        return true;
    }

    // ==================== 阻塞格模型（阶段 4.4） ====================

    /** 清扫过期阻塞格，恢复通行并广播 */
    private void expireBlocks() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = blockedCells.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                writeLog(null, null, "状态变更", "格子 (" + e.getKey() + ") 封锁到期，通道恢复通行");
                pushAlert("info", "格子 (" + e.getKey() + ") 通道恢复通行");
            }
        }
    }

    private boolean isBlocked(String key) {
        Long until = blockedCells.get(key);
        return until != null && until > System.currentTimeMillis();
    }

    /**
     * 注入：格子级通道堵塞（10 秒）。
     * 默认封锁该 AGV 当前格；可选坐标封锁指定格子。已在途、路径经过该格的车辆立即重规划绕行。
     */
    public void injectBlock(Long agvId, Integer x, Integer y) {
        WmsAgvDevice agv = mustExist(agvId);
        int bx = x == null ? agv.getPosX() : x;
        int by = y == null ? agv.getPosY() : y;
        if (bx < 0 || by < 0) {
            throw new IllegalStateException("封锁坐标非法：(" + bx + "," + by + ")");
        }
        String key = cellKey(bx, by);
        blockedCells.put(key, System.currentTimeMillis() + BLOCK_MS);
        writeLog(agvId, null, "告警", agv.getAgvCode() + " 触发通道堵塞：格子 (" + key
                + ") 封锁 10 秒，其他车辆将绕行或等待");
        pushAlert("warn", "格子 (" + key + ") 通道堵塞，封锁 10 秒");
        // 受影响车辆立即重规划（阶段 4.4：绕行优先，不等 10 秒）
        for (Map.Entry<Long, List<int[]>> e : pathQueue.entrySet()) {
            boolean hit = e.getValue().stream().anyMatch(p -> p[0] == bx && p[1] == by);
            if (hit) {
                writeLog(e.getKey(), null, "冲突",
                        agvCode(e.getKey()) + " 路径经过封锁格 (" + key + ")，立即重新规划绕行");
                e.setValue(new ArrayList<>());
            }
        }
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

    /**
     * 分配评分：越小越优先。
     *
     * <p>{@code nearest}：就是曼哈顿距离。
     * <p>{@code nearest-load}：曼哈顿距离 + 负载惩罚系数 × 该车累计完成单数，
     * 让任务在整个车队里摊开（而不是永远由最近的那台车接单）。
     */
    private int assignScore(WmsAgvDevice agv, int[] from, Map<Long, Long> loadMap) {
        int dist = manhattan(agv.getPosX(), agv.getPosY(), from[0], from[1]);
        if (!STRATEGY_NEAREST_LOAD.equals(strategyCode)) {
            return dist;
        }
        long done = loadMap.getOrDefault(agv.getId(), 0L);
        return dist + LOAD_PENALTY_WEIGHT * (int) done;
    }

    /** 各车累计完成任务数（SQL 聚合，一次查完） */
    private Map<Long, Long> completedLoadMap() {
        Map<Long, Long> map = new HashMap<>();
        QueryWrapper<WmsAgvTask> qw = new QueryWrapper<>();
        qw.select("agv_id", "COUNT(*) AS cnt")
                .eq("status", TASK_DONE)
                .isNotNull("agv_id")
                .groupBy("agv_id");
        for (Map<String, Object> row : taskService.getBaseMapper().selectMaps(qw)) {
            Object id = row.get("agv_id");
            Object cnt = row.get("cnt");
            if (id != null && cnt != null) {
                map.put(((Number) id).longValue(), ((Number) cnt).longValue());
            }
        }
        return map;
    }

    /** 当前分配策略编码 */
    public String getStrategy() {
        return strategyCode;
    }

    /** 策略中文名 */
    public static String strategyLabel(String code) {
        return STRATEGY_NEAREST_LOAD.equals(code) ? "就近+负载惩罚" : "就近分配";
    }

    /**
     * 切换分配策略（阶段 5.3 对比实验用）。
     * 对比实验要求"组间只有策略变量不同"，所以策略必须能在运行时切换，
     * 而不是改配置文件重启。
     */
    public void setStrategy(String code) {
        if (!STRATEGY_NEAREST.equals(code) && !STRATEGY_NEAREST_LOAD.equals(code)) {
            throw new IllegalStateException("不支持的分配策略：" + code
                    + "（可选 " + STRATEGY_NEAREST + " / " + STRATEGY_NEAREST_LOAD + "）");
        }
        strategyCode = code;
        writeLog(null, null, "状态变更", "调度策略切换为「" + strategyLabel(code) + "」");
        pushAlert("info", "调度策略已切换为「" + strategyLabel(code) + "」");
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

    // ==================== 实验台：批量建单与一键重置（阶段 5.1） ====================

    /**
     * 批量建单：一次创建 count 个同类型、同起终点的搬运任务。
     *
     * <p>用于论文第 5 章的对比实验——需要在"只改变策略变量"的条件下重复跑同一组负载，
     * 手工建单既慢又容易漏，所以提供批量入口。
     *
     * @return 新建的任务号列表
     */
    public List<String> batchCreateTasks(int count, String taskType, Long fromLocationId,
                                         Long toLocationId, Integer priority) {
        if (count <= 0 || count > 500) {
            throw new IllegalStateException("批量建单数量需在 1~500 之间，当前：" + count);
        }
        String type = (taskType == null || taskType.isBlank()) ? "移库搬运" : taskType;
        List<String> nos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WmsAgvTask task = createTask(type, null, null, fromLocationId, toLocationId,
                    priority == null ? 0 : priority);
            nos.add(task.getTaskNo());
        }
        return nos;
    }

    /**
     * 一键重置：清空全部任务与 AGV 日志，把所有车辆放回待命位并恢复满电，
     * 同时清空内存里的路径队列、占用表、等待标识与回收冷却，让统计从零开始。
     *
     * <p>这是论文第 5 章对比实验"可复现"的基础：每组实验都从完全相同的初态出发，
     * 组与组之间只有策略变量不同。
     *
     * @param agvCount  参与实验的车辆数（按 id 升序取前 N 台，其余停用）；null 表示全部启用
     * @param strategy  分配策略；null 表示保持当前策略
     */
    public Map<String, Object> resetSimulator(Integer agvCount, String strategy) {
        Long taskCount = taskService.lambdaQuery().count();
        int tasks = taskCount == null ? 0 : taskCount.intValue();
        taskService.remove(null);
        logService.remove(null);
        if (strategy != null && !strategy.isBlank()) {
            setStrategy(strategy);
        }
        List<WmsAgvDevice> fleet = deviceService.list(
                Wrappers.<WmsAgvDevice>lambdaQuery().orderByAsc(WmsAgvDevice::getId));
        int limit = (agvCount == null || agvCount <= 0) ? fleet.size() : Math.min(agvCount, fleet.size());
        for (int i = 0; i < fleet.size(); i++) {
            WmsAgvDevice d = fleet.get(i);
            d.setStatus(AGV_IDLE);
            d.setBattery(100);
            // 只有前 N 台参与实验，其余停用（对比实验要控制车辆数这个变量）
            d.setEnabled(i < limit ? 1 : 0);
            d.setPosX(d.getHomeX() == null ? 0 : d.getHomeX());
            d.setPosY(d.getHomeY() == null ? 0 : d.getHomeY());
            deviceService.updateById(d);
            pushAgv(d);
        }
        pathQueue.clear();
        pathPhase.clear();
        pathTarget.clear();
        moveAcc.clear();
        waitTicks.clear();
        cellOwner.clear();
        blockedCells.clear();
        waitingSince.clear();
        waitingReason.clear();
        waitingThisTick.clear();
        waitStage.clear();
        recycleCooldown.clear();
        recycleAvoidAgv.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clearedTasks", tasks);
        result.put("agvCount", limit);
        result.put("totalAgv", fleet.size());
        result.put("strategy", strategyCode);
        result.put("strategyLabel", strategyLabel(strategyCode));
        result.put("message", "已重置：任务与日志清空，车辆回待命位、满电、内存状态清零");
        writeLog(null, null, "状态变更", "实验台一键重置：清空任务与日志，车辆回待命位，策略「"
                + strategyLabel(strategyCode) + "」，参与车辆 " + limit + " 台");
        return result;
    }

    /** 取消单个任务：释放占用的 AGV */
    public void cancelTask(Long taskId, String reason) {
        WmsAgvTask task = taskService.getById(taskId);
        if (task == null || task.getStatus() >= TASK_DONE) {
            return;
        }
        assertTransition(task, TASK_CANCELED);
        freeAgv(task.getAgvId());
        if (task.getAgvId() != null) {
            clearPath(task.getAgvId());
            clearWaiting(task.getAgvId());
        }
        task.setStatus(TASK_CANCELED);
        if (task.getFinishTime() == null) {
            // 取消也是任务终点，补结束时间，供利用率/时长统计使用
            task.setFinishTime(LocalDateTime.now());
        }
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

    /**
     * 回收任务：在途任务退回待分配队列（保留 priority，紧急任务自然插队），
     * AGV 释放为空闲（低电时下一周期由 autoChargeTick 转入充电）。
     *
     * <p><b>修 D4</b>：回收后给任务设一段冷却，并记下"刚出过问题的那台车"，
     * 避免同一任务在几秒内被立刻派回同一台车、再原地死锁、再回收——形成活锁。
     *
     * <p><b>修 D5</b>：回收时清空各阶段时间戳。任务回到队列后会重新走一遍完整流程，
     * 沿用上一轮的 pick/drop 时间戳会让任务时间线出现"倒序"（修 D5）。
     */
    private void recycleTask(WmsAgvTask task, WmsAgvDevice agv, String reason) {
        assertTransition(task, TASK_PENDING);
        persistRecycle(task, TASK_PENDING, reason);
        waitTicks.remove(task.getId());
        moveAcc.remove(agv.getId());
        clearPath(agv.getId());
        clearWaiting(agv.getId());
        agv.setStatus(AGV_IDLE);
        deviceService.updateById(agv);
        // 冷却 + 避让（修 D4：避免活锁）
        markRecycleCooldown(task.getId(), agv.getId());
        writeLog(agv.getId(), task.getId(), "告警",
                agv.getAgvCode() + " " + reason + "，任务「" + task.getTaskNo() + "」退回待分配（优先级 "
                        + task.getPriority() + " 保留，"
                        + (RECYCLE_COOLDOWN_MS / 1000) + " 秒内不再派给该车）");
        pushTask(task);
        pushAgv(agv);
    }

    // ==================== 异常模拟与自动重分配 ====================

    /** 注入：故障停机。其执行中的任务自动回收为待分配，由其他空闲 AGV 接手 */
    public void injectFault(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        agv.setStatus(AGV_FAULT);
        deviceService.updateById(agv);
        moveAcc.remove(agvId);
        clearPath(agvId);
        clearWaiting(agvId);
        // 回收该 AGV 未完成的任务 → 重新进入待分配队列（紧急任务保持高优先级，自然插队）
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .eq(WmsAgvTask::getAgvId, agvId)
                .between(WmsAgvTask::getStatus, TASK_TO_PICK, TASK_RETURNING)
                .list();
        for (WmsAgvTask task : tasks) {
            assertTransition(task, TASK_PENDING);
            persistRecycle(task, TASK_PENDING, "AGV 故障停机，任务已回收等待重新分配");
            waitTicks.remove(task.getId());
            markRecycleCooldown(task.getId(), agvId);
            writeLog(agvId, task.getId(), "故障",
                    agv.getAgvCode() + " 故障停机，任务「" + task.getTaskNo() + "」已回收，等待改派");
            pushTask(task);
        }
        writeLog(agvId, null, "故障", agv.getAgvCode() + " 故障停机！");
        pushAlert("error", agv.getAgvCode() + " 故障停机！" + (tasks.isEmpty() ? "" : "已自动改派 " + tasks.size() + " 个任务"));
        pushAgv(agv);
    }

    /** 注入：电量不足（降到 8%）：告警 + 任务回收 + 车辆转入回充，由其他空闲 AGV 接手 */
    public void injectLowBattery(Long agvId) {
        WmsAgvDevice agv = mustExist(agvId);
        agv.setBattery(8);
        // 回收该 AGV 未完成的任务 → 退回待分配队列，priority 保持不变
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .eq(WmsAgvTask::getAgvId, agvId)
                .between(WmsAgvTask::getStatus, TASK_TO_PICK, TASK_RETURNING)
                .list();
        for (WmsAgvTask task : tasks) {
            assertTransition(task, TASK_PENDING);
            persistRecycle(task, TASK_PENDING, "AGV 电量不足（8%），任务已回收等待重新分配");
            waitTicks.remove(task.getId());
            markRecycleCooldown(task.getId(), agvId);
            writeLog(agvId, task.getId(), "告警",
                    agv.getAgvCode() + " 电量不足（8%），任务「" + task.getTaskNo() + "」已回收，等待改派");
            pushTask(task);
        }
        moveAcc.remove(agvId);
        clearPath(agvId);
        clearWaiting(agvId);
        if (agv.getStatus() == AGV_RUNNING) {
            agv.setStatus(AGV_IDLE);
        }
        deviceService.updateById(agv);
        writeLog(agvId, null, "告警", agv.getAgvCode() + " 电量不足告警（8%），停止接单并回充");
        pushAlert("warn", agv.getAgvCode() + " 电量不足，任务已回队列，车辆回充");
        pushAgv(agv);
    }

    /** 模拟行驶耗电：只改电量，不回收任务；在途车辆由 tick 的电量兜底逻辑决定回收/充电 */
    public void drainBattery(Long agvId, int battery) {
        WmsAgvDevice agv = mustExist(agvId);
        int target = Math.max(0, Math.min(100, battery));
        agv.setBattery(target);
        if (agv.getStatus() == AGV_IDLE && target <= AUTO_CHARGE_BATTERY) {
            // 空闲且低电：直接进入充电，避免下一 tick 才生效
            agv.setStatus(AGV_CHARGING);
            writeLog(agvId, null, "充电", agv.getAgvCode() + " 低电自动开始充电（模拟耗电至 " + target + "%）");
        } else {
            writeLog(agvId, null, "状态变更",
                    agv.getAgvCode() + " 电量模拟耗至 " + target + "%（用于验证在途兜底）");
        }
        deviceService.updateById(agv);
        pushAgv(agv);
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
            assertTransition(task, TASK_PENDING);
            persistRecycle(task, TASK_PENDING, "取货失败，任务退回待分配");
            waitTicks.remove(task.getId());
            markRecycleCooldown(task.getId(), agvId);
            writeLog(agvId, task.getId(), "告警",
                    agv.getAgvCode() + " 取货失败，任务「" + task.getTaskNo() + "」退回待分配");
            pushTask(task);
        }
        agv.setStatus(AGV_IDLE);
        clearPath(agvId);
        clearWaiting(agvId);
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

    // ==================== 协同状态快照（阶段 4 验收） ====================

    /** 当前多车协同快照：占用表 / 路径队列 / 阻塞格 / 等待列表 */
    public Map<String, Object> gridSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("blocked", new ArrayList<>(blockedCells.keySet()));
        snapshot.put("occupancy", new HashMap<>(cellOwner));
        Map<String, Object> paths = new LinkedHashMap<>();
        for (Map.Entry<Long, List<int[]>> e : pathQueue.entrySet()) {
            WmsAgvDevice agv = deviceService.getById(e.getKey());
            if (agv == null) {
                continue;
            }
            paths.put(agv.getAgvCode(), e.getValue().stream()
                    .map(p -> p[0] + "," + p[1])
                    .toList());
        }
        snapshot.put("paths", paths);
        List<Map<String, Object>> waiting = new ArrayList<>();
        for (Map.Entry<Long, Long> e : waitingSince.entrySet()) {
            WmsAgvDevice agv = deviceService.getById(e.getKey());
            if (agv == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agvId", agv.getId());
            item.put("agvCode", agv.getAgvCode());
            item.put("since", e.getValue());
            item.put("reason", waitingReason.getOrDefault(agv.getId(), "等待"));
            waiting.add(item);
        }
        snapshot.put("waiting", waiting);
        snapshot.put("blockMs", BLOCK_MS);
        snapshot.put("transitionTable", transitionTable());
        return snapshot;
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

    // ==================== 小工具 ====================

    private String agvCode(Long agvId) {
        WmsAgvDevice agv = deviceService.getById(agvId);
        return agv == null ? "AGV#" + agvId : agv.getAgvCode();
    }

    private void clearPath(Long agvId) {
        pathQueue.remove(agvId);
        pathPhase.remove(agvId);
        pathTarget.remove(agvId);
    }

    /** 该任务是否仍处于回收冷却期（修 D4：冷却期内不派单，杜绝活锁） */
    private boolean isInRecycleCooldown(Long taskId) {
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

    /** 标记任务回收后的冷却与避让（修 D4） */
    private void markRecycleCooldown(Long taskId, Long agvId) {
        recycleCooldown.put(taskId, System.currentTimeMillis() + RECYCLE_COOLDOWN_MS);
        if (agvId != null) {
            recycleAvoidAgv.put(taskId, agvId);
        }
    }

    /**
     * 回收任务时的落库：显式把 {@code agv_id} 与各阶段时间戳写成 NULL。
     *
     * <p><b>关键点</b>：MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段
     * （{@code FieldStrategy.NOT_NULL}），所以"把对象上的 agvId / 时间戳置空再 updateById"
     * <b>写不进数据库</b>——旧值会原样留着，表现为：任务虽然回到待分配，
     * 但仍挂着上一台车的 id；时间线也因为沿用上一轮的时间戳而出现倒序（缺陷 D5）。
     * 这里统一用 {@code LambdaUpdateWrapper} 显式 {@code set(col, null)}。
     *
     * @param task     任务对象（内存字段同步置空）
     * @param status   目标状态（通常是 TASK_PENDING）
     * @param errorMsg 异常/回收说明
     */
    private void persistRecycle(WmsAgvTask task, int status, String errorMsg) {
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

    private static String cellKey(int x, int y) {
        return x + "," + y;
    }

    private static boolean isAdjacent(int x1, int y1, int[] cell) {
        return Math.abs(x1 - cell[0]) + Math.abs(y1 - cell[1]) == 1;
    }

    private int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }
}
