package com.wms.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.annotation.OperationLog;
import com.wms.annotation.RequireRole;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvLog;
import com.wms.entity.WmsAgvTask;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.service.AgvEngineService;
import com.wms.service.WmsAgvDeviceService;
import com.wms.service.WmsAgvLogService;
import com.wms.service.WmsAgvTaskService;
import com.wms.service.WmsLocationService;
import com.wms.service.WmsMaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AGV 搬运任务（v2.0 WCS-AGV 扩展）
 */
@Tag(name = "AGV搬运任务")
@RestController
@RequestMapping("/agv/task")
@RequiredArgsConstructor
public class AgvTaskController {

    private final WmsAgvTaskService taskService;
    private final WmsAgvDeviceService deviceService;
    private final WmsAgvLogService logService;
    private final WmsLocationService locationService;
    private final WmsMaterialService materialService;
    private final AgvEngineService engine;

    @Operation(summary = "任务分页列表（含 AGV 编号/库位/物料名称）")
    @GetMapping("/page")
    public Result<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String taskType,
                                                  @RequestParam(required = false) Integer status) {
        Page<WmsAgvTask> page = taskService.lambdaQuery()
                .and(StrUtil.isNotBlank(keyword), q -> q.like(WmsAgvTask::getTaskNo, keyword)
                        .or().like(WmsAgvTask::getOrderNo, keyword))
                .eq(StrUtil.isNotBlank(taskType), WmsAgvTask::getTaskType, taskType)
                .eq(status != null, WmsAgvTask::getStatus, status)
                .orderByDesc(WmsAgvTask::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(enrich(page));
    }

    @Operation(summary = "进行中的任务（监控页用）")
    @GetMapping("/active")
    public Result<List<Map<String, Object>>> active() {
        List<WmsAgvTask> tasks = taskService.lambdaQuery()
                .lt(WmsAgvTask::getStatus, AgvEngineService.TASK_DONE)
                .orderByDesc(WmsAgvTask::getPriority)
                .orderByAsc(WmsAgvTask::getId)
                .list();
        Page<WmsAgvTask> wrapper = new Page<>();
        wrapper.setRecords(tasks);
        return Result.ok(enrich(wrapper).getRecords());
    }

    @Operation(summary = "手动创建任务（演示优先级插队用）")
    @OperationLog("手动创建AGV任务")
    @RequireRole({"ADMIN", "DISPATCHER"})
    @PostMapping("/create")
    public Result<String> create(@RequestBody WmsAgvTask dto) {
        if (!engine.isEnabled()) {
            throw new BizException("AGV 调度已关闭（agv.enabled=false）");
        }
        if (StrUtil.isBlank(dto.getTaskType())) {
            throw new BizException("请选择任务类型");
        }
        WmsAgvTask task = engine.createTask(dto.getTaskType(), null, dto.getMaterialId(),
                dto.getFromLocationId(), dto.getToLocationId(),
                dto.getPriority() != null && dto.getPriority() == 1 ? 1 : 0);
        return Result.ok(task.getTaskNo());
    }

    @Operation(summary = "AGV 任务统计（数据看板图表用，v2.0 E3）")
    @GetMapping("/stat")
    public Result<Map<String, Object>> stat() {
        List<WmsAgvTask> all = taskService.list();

        // 近14天每日任务完成量（按完成时间归天，无数据补 0）
        LocalDate today = LocalDate.now();
        Map<String, Long> doneMap = all.stream()
                .filter(t -> t.getStatus() == AgvEngineService.TASK_DONE && t.getFinishTime() != null)
                .collect(Collectors.groupingBy(t -> t.getFinishTime().toLocalDate().toString(), Collectors.counting()));
        List<String> labels = new ArrayList<>();
        List<Long> donePerDay = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            labels.add(day.substring(5)); // 只显示 MM-dd，避免横轴过密
            donePerDay.add(doneMap.getOrDefault(day, 0L));
        }

        // 任务类型占比
        List<Map<String, Object>> typeRatio = all.stream()
                .filter(t -> StrUtil.isNotBlank(t.getTaskType()))
                .collect(Collectors.groupingBy(WmsAgvTask::getTaskType, Collectors.counting()))
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());

        // 故障/告警次数（来自 AGV 运行日志表）
        long faultCount = logService.lambdaQuery().eq(WmsAgvLog::getLogType, "故障").count();
        long alertCount = logService.lambdaQuery().eq(WmsAgvLog::getLogType, "告警").count();

        // 平均任务耗时（秒）：已完成任务 开始执行→完成
        double avgSec = all.stream()
                .filter(t -> t.getStatus() == AgvEngineService.TASK_DONE
                        && t.getStartTime() != null && t.getFinishTime() != null)
                .mapToLong(t -> Duration.between(t.getStartTime(), t.getFinishTime()).getSeconds())
                .average().orElse(0);

        long totalDone = all.stream().filter(t -> t.getStatus() == AgvEngineService.TASK_DONE).count();

        // 阶段 3.3：设备利用率 / 故障率 / 完成率 / 冲突数 / 充电次数
        long enabledAgvs = deviceService.lambdaQuery().eq(WmsAgvDevice::getEnabled, 1).count();
        long chargeCount = logService.lambdaQuery().eq(WmsAgvLog::getLogType, "充电").count();
        long conflictCount = logService.lambdaQuery().eq(WmsAgvLog::getLogType, "冲突").count();

        // 利用率口径统一在 AgvStats，看板/导出复用同一实现
        double utilization = com.wms.common.AgvStats.utilization(all, enabledAgvs, java.time.LocalDateTime.now());
        double completionRate = all.isEmpty() ? 0
                : Math.round(totalDone * 1000.0 / all.size()) / 10.0;
        double faultRate = all.isEmpty() ? 0
                : Math.min(100, Math.round(faultCount * 1000.0 / all.size()) / 10.0);

        // 阶段 5.2：里程与空驶率（口径见计划文档附 D）
        long emptySum = all.stream().mapToLong(t -> t.getEmptyDistance() == null ? 0L : t.getEmptyDistance()).sum();
        long loadedSum = all.stream().mapToLong(t -> t.getLoadedDistance() == null ? 0L : t.getLoadedDistance()).sum();
        long totalDist = emptySum + loadedSum;
        double emptyRate = totalDist == 0 ? 0
                : Math.round(emptySum * 1000.0 / totalDist) / 10.0;

        Map<String, Object> result = new HashMap<>();
        result.put("labels", labels);
        result.put("donePerDay", donePerDay);
        result.put("typeRatio", typeRatio);
        result.put("faultCount", faultCount);
        result.put("alertCount", alertCount);
        result.put("avgDurationSec", Math.round(avgSec * 10) / 10.0);
        result.put("totalDone", totalDone);
        result.put("totalTask", all.size());
        result.put("enabledAgv", enabledAgvs);
        result.put("utilization", utilization);
        result.put("completionRate", completionRate);
        result.put("faultRate", faultRate);
        result.put("conflictCount", conflictCount);
        result.put("chargeCount", chargeCount);
        result.put("emptyDistance", emptySum);
        result.put("loadedDistance", loadedSum);
        result.put("totalDistance", totalDist);
        result.put("emptyRate", emptyRate);
        return Result.ok(result);
    }

    @Operation(summary = "取消任务")
    @OperationLog("取消AGV任务")
    @RequireRole({"ADMIN", "DISPATCHER"})
    @PutMapping("/cancel/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        WmsAgvTask task = taskService.getById(id);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        if (task.getStatus() >= AgvEngineService.TASK_DONE) {
            throw new BizException("任务已结束，不能取消");
        }
        engine.cancelTask(id, "手动取消");
        return Result.ok();
    }

    @Operation(summary = "调整任务优先级（紧急插队）")
    @OperationLog("调整AGV任务优先级")
    @RequireRole({"ADMIN", "DISPATCHER"})
    @PutMapping("/priority/{id}")
    public Result<Void> priority(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        WmsAgvTask task = taskService.getById(id);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        if (task.getStatus() != AgvEngineService.TASK_PENDING) {
            throw new BizException("只有待分配任务可以调整优先级");
        }
        Integer priority = body.get("priority") == null ? 1 : Integer.valueOf(String.valueOf(body.get("priority")));
        if (priority != 0 && priority != 1) {
            throw new BizException("优先级只能是 0（普通）或 1（紧急）");
        }
        task.setPriority(priority);
        taskService.updateById(task);
        engine.writeTaskLog(task, "优先级调整",
                "任务「" + task.getTaskNo() + "」优先级调整为" + (priority == 1 ? "紧急" : "普通"));
        return Result.ok();
    }

    @Operation(summary = "8 态任务转换表（阶段 4.5）")
    @GetMapping("/transitions")
    public Result<List<Map<String, Object>>> transitions() {
        return Result.ok(engine.transitionTable());
    }

    @Operation(summary = "批量建单（阶段 5.1 实验台）：一次创建多个同类型同起终点任务")
    @OperationLog("批量创建AGV任务")
    @RequireRole({"ADMIN", "DISPATCHER"})
    @PostMapping("/batch")
    public Result<List<String>> batchCreate(@RequestBody Map<String, Object> body) {
        try {
            int count = body.get("count") == null ? 10 : Integer.parseInt(String.valueOf(body.get("count")));
            String taskType = body.get("taskType") == null ? null : String.valueOf(body.get("taskType"));
            Long fromId = body.get("fromLocationId") == null ? null
                    : Long.valueOf(String.valueOf(body.get("fromLocationId")));
            Long toId = body.get("toLocationId") == null ? null
                    : Long.valueOf(String.valueOf(body.get("toLocationId")));
            Integer priority = body.get("priority") == null ? 0
                    : Integer.valueOf(String.valueOf(body.get("priority")));
            return Result.ok(engine.batchCreateTasks(count, taskType, fromId, toId, priority));
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
    }

    @Operation(summary = "一键重置实验台（阶段 5.1）：清空任务与日志，车辆回待命位满电")
    @OperationLog("重置AGV实验台")
    @RequireRole({"ADMIN"})
    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(@RequestBody(required = false) Map<String, Object> body) {
        Integer agvCount = body == null || body.get("agvCount") == null ? null
                : Integer.valueOf(String.valueOf(body.get("agvCount")));
        String strategy = body == null || body.get("strategy") == null ? null
                : String.valueOf(body.get("strategy"));
        try {
            return Result.ok(engine.resetSimulator(agvCount, strategy));
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
    }

    @Operation(summary = "对比实验指标汇总（阶段 5.4：完成率/平均完成时间/空驶率/冲突次数/负载均衡）")
    @GetMapping("/experiment")
    public Result<Map<String, Object>> experiment() {
        List<WmsAgvTask> all = taskService.list();
        long total = all.size();
        long done = all.stream().filter(t -> t.getStatus() == AgvEngineService.TASK_DONE).count();

        // 平均完成时间（秒）：已完成任务 开始执行 → 完成
        double avgSec = all.stream()
                .filter(t -> t.getStatus() == AgvEngineService.TASK_DONE
                        && t.getStartTime() != null && t.getFinishTime() != null)
                .mapToLong(t -> Duration.between(t.getStartTime(), t.getFinishTime()).getSeconds())
                .average().orElse(0);

        // 里程与空驶率
        long emptySum = all.stream().mapToLong(t -> t.getEmptyDistance() == null ? 0L : t.getEmptyDistance()).sum();
        long loadedSum = all.stream().mapToLong(t -> t.getLoadedDistance() == null ? 0L : t.getLoadedDistance()).sum();
        long totalDist = emptySum + loadedSum;
        double emptyRate = totalDist == 0 ? 0 : Math.round(emptySum * 1000.0 / totalDist) / 10.0;

        // 冲突与等待事件（来自 wms_agv_log，不新增表）
        long conflictCount = logService.lambdaQuery().eq(WmsAgvLog::getLogType, "冲突").count();
        long waitCount = logService.lambdaQuery().eq(WmsAgvLog::getLogType, "冲突").count();

        // 各车负载分布 + 均衡度（标准差越小越均衡）
        Map<Long, Long> loadByAgv = new HashMap<>();
        for (WmsAgvTask t : all) {
            if (t.getStatus() == AgvEngineService.TASK_DONE && t.getAgvId() != null) {
                loadByAgv.merge(t.getAgvId(), 1L, Long::sum);
            }
        }
        Map<String, Object> agvLoad = new LinkedHashMap<>();
        double sum = 0;
        double sumSq = 0;
        for (Map.Entry<Long, Long> e : loadByAgv.entrySet()) {
            WmsAgvDevice d = deviceService.getById(e.getKey());
            agvLoad.put(d == null ? ("AGV#" + e.getKey()) : d.getAgvCode(), e.getValue());
            sum += e.getValue();
            sumSq += (double) e.getValue() * e.getValue();
        }
        int n = loadByAgv.size();
        double mean = n == 0 ? 0 : sum / n;
        double variance = n == 0 ? 0 : sumSq / n - mean * mean;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", engine.getStrategy());
        result.put("strategyLabel", AgvEngineService.strategyLabel(engine.getStrategy()));
        result.put("agvCount", deviceService.lambdaQuery().eq(WmsAgvDevice::getEnabled, 1).count());
        result.put("totalTask", total);
        result.put("doneTask", done);
        result.put("completionRate", total == 0 ? 0 : Math.round(done * 1000.0 / total) / 10.0);
        result.put("avgDurationSec", Math.round(avgSec * 10) / 10.0);
        result.put("emptyRate", emptyRate);
        result.put("totalDistance", totalDist);
        result.put("conflictCount", conflictCount);
        result.put("waitCount", waitCount);
        result.put("agvLoad", agvLoad);
        result.put("loadStdDev", Math.round(Math.sqrt(Math.max(0, variance)) * 100) / 100.0);
        result.put("utilization", com.wms.common.AgvStats.utilization(all,
                deviceService.lambdaQuery().eq(WmsAgvDevice::getEnabled, 1).count(),
                LocalDateTime.now()));
        return Result.ok(result);
    }

    @Operation(summary = "查询当前分配策略（阶段 5.3 对比实验）")
    @GetMapping("/strategy")
    public Result<Map<String, Object>> strategy() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", engine.getStrategy());
        map.put("label", AgvEngineService.strategyLabel(engine.getStrategy()));
        map.put("options", List.of(
                Map.of("code", AgvEngineService.STRATEGY_NEAREST, "label", "就近分配"),
                Map.of("code", AgvEngineService.STRATEGY_NEAREST_LOAD, "label", "就近+负载惩罚")));
        return Result.ok(map);
    }

    @Operation(summary = "切换分配策略（阶段 5.3 对比实验：nearest / nearest-load）")
    @OperationLog("切换AGV调度策略")
    @RequireRole({"ADMIN"})
    @PutMapping("/strategy/{code}")
    public Result<Map<String, Object>> setStrategy(@PathVariable String code) {
        try {
            engine.setStrategy(code);
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", engine.getStrategy());
        map.put("label", AgvEngineService.strategyLabel(engine.getStrategy()));
        return Result.ok(map);
    }

    @Operation(summary = "管理员手动流转任务状态；非法流转返回明确错误（阶段 4.5）")
    @OperationLog("手动流转AGV任务状态")
    @RequireRole({"ADMIN"})
    @PutMapping("/transition/{id}/{status}")
    public Result<Void> transition(@PathVariable Long id, @PathVariable Integer status) {
        try {
            engine.forceTransition(id, status);
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
        return Result.ok();
    }

    /** 补充显示字段：agvCode / fromCode / toCode / materialName */
    private Page<Map<String, Object>> enrich(Page<WmsAgvTask> page) {
        Map<Long, String> agvMap = deviceService.list().stream()
                .collect(Collectors.toMap(WmsAgvDevice::getId, WmsAgvDevice::getAgvCode));
        Map<Long, String> locMap = locationService.list().stream()
                .collect(Collectors.toMap(WmsLocation::getId, WmsLocation::getLocationCode));
        Map<Long, String> materialMap = materialService.list().stream()
                .collect(Collectors.toMap(WmsMaterial::getId, WmsMaterial::getMaterialName));
        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(t -> {
            Map<String, Object> row = BeanUtil.beanToMap(t);
            row.put("agvCode", t.getAgvId() == null ? "-" : agvMap.getOrDefault(t.getAgvId(), "?"));
            row.put("fromCode", t.getFromLocationId() == null ? "月台" : locMap.getOrDefault(t.getFromLocationId(), "?"));
            row.put("toCode", t.getToLocationId() == null ? "月台" : locMap.getOrDefault(t.getToLocationId(), "?"));
            row.put("materialName", t.getMaterialId() == null ? "-" : materialMap.getOrDefault(t.getMaterialId(), "?"));
            return row;
        }).collect(Collectors.toList()));
        return result;
    }
}
