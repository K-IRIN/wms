package com.wms.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.annotation.OperationLog;
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
import java.util.ArrayList;
import java.util.HashMap;
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

        Map<String, Object> result = new HashMap<>();
        result.put("labels", labels);
        result.put("donePerDay", donePerDay);
        result.put("typeRatio", typeRatio);
        result.put("faultCount", faultCount);
        result.put("alertCount", alertCount);
        result.put("avgDurationSec", Math.round(avgSec * 10) / 10.0);
        result.put("totalDone", totalDone);
        result.put("totalTask", all.size());
        return Result.ok(result);
    }

    @Operation(summary = "取消任务")
    @OperationLog("取消AGV任务")
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
