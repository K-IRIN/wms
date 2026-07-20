package com.wms.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.annotation.OperationLog;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvTask;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.service.AgvEngineService;
import com.wms.service.WmsAgvDeviceService;
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
