package com.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.annotation.OperationLog;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvLog;
import com.wms.service.WmsAgvDeviceService;
import com.wms.service.WmsAgvLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AGV 设备档案管理（v2.0 WCS-AGV 扩展）
 */
@Tag(name = "AGV设备档案管理")
@RestController
@RequestMapping("/agv/device")
@RequiredArgsConstructor
public class AgvDeviceController {

    /** 运行状态：0空闲 1运行中 2充电中 3故障 */
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_RUNNING = 1;
    private static final int STATUS_CHARGING = 2;
    private static final int STATUS_FAULT = 3;

    private final WmsAgvDeviceService deviceService;
    private final WmsAgvLogService logService;

    @Operation(summary = "AGV 设备分页列表")
    @GetMapping("/page")
    public Result<Page<WmsAgvDevice>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) Integer enabled) {
        Page<WmsAgvDevice> page = deviceService.lambdaQuery()
                .and(StrUtil.isNotBlank(keyword), q -> q.like(WmsAgvDevice::getAgvCode, keyword)
                        .or().like(WmsAgvDevice::getAgvName, keyword))
                .eq(status != null, WmsAgvDevice::getStatus, status)
                .eq(enabled != null, WmsAgvDevice::getEnabled, enabled)
                .orderByAsc(WmsAgvDevice::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "AGV 设备全量列表（下拉用）")
    @GetMapping("/list")
    public Result<List<WmsAgvDevice>> list() {
        return Result.ok(deviceService.lambdaQuery().orderByAsc(WmsAgvDevice::getId).list());
    }

    @Operation(summary = "新增 AGV 设备")
    @OperationLog("新增AGV设备")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody WmsAgvDevice device) {
        checkCodeUnique(device.getAgvCode(), null);
        device.setId(null);
        if (device.getBattery() == null) {
            device.setBattery(100);
        }
        if (device.getStatus() == null) {
            device.setStatus(STATUS_IDLE);
        }
        if (device.getEnabled() == null) {
            device.setEnabled(1);
        }
        // 初始位置默认放在待命区
        if (device.getPosX() == null) {
            device.setPosX(device.getHomeX() == null ? 0 : device.getHomeX());
        }
        if (device.getPosY() == null) {
            device.setPosY(device.getHomeY() == null ? 0 : device.getHomeY());
        }
        deviceService.save(device);
        writeLog(device.getId(), "状态变更", "设备建档，编号 " + device.getAgvCode());
        return Result.ok();
    }

    @Operation(summary = "编辑 AGV 设备")
    @OperationLog("编辑AGV设备")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody WmsAgvDevice device) {
        if (device.getId() == null) {
            throw new BizException("缺少设备ID");
        }
        checkCodeUnique(device.getAgvCode(), device.getId());
        // 运行状态、电量由调度引擎维护，不允许手工改
        device.setStatus(null);
        device.setBattery(null);
        deviceService.updateById(device);
        return Result.ok();
    }

    @Operation(summary = "启用/停用 AGV 设备")
    @OperationLog("启用/停用AGV设备")
    @PutMapping("/status/{id}/{enabled}")
    public Result<Void> status(@PathVariable Long id, @PathVariable Integer enabled) {
        WmsAgvDevice exist = deviceService.getById(id);
        if (exist == null) {
            throw new BizException("设备不存在");
        }
        if (enabled != null && enabled == 0 && exist.getStatus() == STATUS_RUNNING) {
            throw new BizException("设备正在执行任务，不能停用");
        }
        WmsAgvDevice d = new WmsAgvDevice();
        d.setId(id);
        d.setEnabled(enabled);
        deviceService.updateById(d);
        writeLog(id, "状态变更", (enabled == 1 ? "设备启用" : "设备停用"));
        return Result.ok();
    }

    @Operation(summary = "开始充电")
    @OperationLog("AGV开始充电")
    @PutMapping("/charge/{id}")
    public Result<Void> charge(@PathVariable Long id) {
        WmsAgvDevice exist = deviceService.getById(id);
        if (exist == null) {
            throw new BizException("设备不存在");
        }
        if (exist.getStatus() == STATUS_RUNNING) {
            throw new BizException("设备正在执行任务，不能充电");
        }
        if (exist.getStatus() == STATUS_FAULT) {
            throw new BizException("设备故障中，请先排除故障");
        }
        WmsAgvDevice d = new WmsAgvDevice();
        d.setId(id);
        d.setStatus(STATUS_CHARGING);
        deviceService.updateById(d);
        writeLog(id, "充电", "开始充电，当前电量 " + exist.getBattery() + "%");
        return Result.ok();
    }

    @Operation(summary = "完成充电")
    @OperationLog("AGV完成充电")
    @PutMapping("/charge/finish/{id}")
    public Result<Void> chargeFinish(@PathVariable Long id) {
        WmsAgvDevice exist = deviceService.getById(id);
        if (exist == null) {
            throw new BizException("设备不存在");
        }
        if (exist.getStatus() != STATUS_CHARGING) {
            throw new BizException("设备当前不在充电中");
        }
        WmsAgvDevice d = new WmsAgvDevice();
        d.setId(id);
        d.setStatus(STATUS_IDLE);
        d.setBattery(100);
        deviceService.updateById(d);
        writeLog(id, "充电", "充电完成，电量恢复 100%");
        return Result.ok();
    }

    @Operation(summary = "删除 AGV 设备")
    @OperationLog("删除AGV设备")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        WmsAgvDevice exist = deviceService.getById(id);
        if (exist != null && exist.getStatus() == STATUS_RUNNING) {
            throw new BizException("设备正在执行任务，不能删除");
        }
        deviceService.removeById(id);
        return Result.ok();
    }

    /** 校验编号唯一 */
    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            throw new BizException("AGV编号不能为空");
        }
        long count = deviceService.lambdaQuery()
                .eq(WmsAgvDevice::getAgvCode, code)
                .ne(excludeId != null, WmsAgvDevice::getId, excludeId)
                .count();
        if (count > 0) {
            throw new BizException("AGV编号已存在：" + code);
        }
    }

    /** 写 AGV 运行日志 */
    private void writeLog(Long agvId, String logType, String content) {
        WmsAgvLog log = new WmsAgvLog();
        log.setAgvId(agvId);
        log.setLogType(logType);
        log.setContent(content);
        logService.save(log);
    }
}
