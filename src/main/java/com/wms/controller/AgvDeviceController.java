package com.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.annotation.OperationLog;
import com.wms.annotation.RequireRole;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsAgvDevice;
import com.wms.entity.WmsAgvLog;
import com.wms.service.WmsAgvDeviceService;
import com.wms.service.WmsAgvLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AGV 设备档案管理（v2.0 WCS-AGV 扩展）
 *
 * <p>权限说明：查询类接口对所有登录用户开放（地图/监控页需要读设备列表）；
 * 六个写接口（建档/编辑/启停/充电/完成充电/删除）仅管理员与设备运维员可操作，
 * 由 {@link RequireRole} 注解 + AOP 切面统一拦截，未授权返回 403。
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

    @Operation(summary = "AGV 运行/告警日志分页")
    @GetMapping("/log/page")
    public Result<Page<WmsAgvLog>> logPage(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) Long agvId,
                                           @RequestParam(required = false) String logType) {
        Page<WmsAgvLog> page = logService.lambdaQuery()
                .eq(agvId != null, WmsAgvLog::getAgvId, agvId)
                .eq(StrUtil.isNotBlank(logType), WmsAgvLog::getLogType, logType)
                .orderByDesc(WmsAgvLog::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "告警事件列表（可回看，阶段 6.3）")
    @GetMapping("/alerts")
    public Result<Page<Map<String, Object>>> alerts(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "10") long pageSize,
                                                    @RequestParam(required = false) String level) {
        // 三类需要回看的事件：告警 / 故障 / 冲突
        List<String> types = new ArrayList<>(List.of("告警", "故障", "冲突"));
        if (StrUtil.isNotBlank(level) && !"all".equals(level)) {
            types = types.stream().filter(t -> level.equals(t)).toList();
        }
        Page<WmsAgvLog> page = logService.lambdaQuery()
                .in(WmsAgvLog::getLogType, types)
                .orderByDesc(WmsAgvLog::getId)
                .page(new Page<>(pageNum, pageSize));
        // 补上设备编号，列表直接可读
        Map<Long, String> codeMap = deviceService.list().stream()
                .collect(Collectors.toMap(WmsAgvDevice::getId, WmsAgvDevice::getAgvCode));
        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("agvCode", r.getAgvId() == null ? "系统" : codeMap.getOrDefault(r.getAgvId(), "AGV#" + r.getAgvId()));
            row.put("logType", r.getLogType());
            row.put("content", r.getContent());
            row.put("createTime", r.getCreateTime());
            return row;
        }).collect(Collectors.toList()));
        return Result.ok(result);
    }

    @Operation(summary = "导出 AGV 运行报表（Excel：设备台账 + 告警记录，阶段 6.3）")
    @OperationLog("导出AGV运行报表")
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("AGV运行报表", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        List<WmsAgvDevice> devices = deviceService.list(
                Wrappers.<WmsAgvDevice>lambdaQuery().orderByAsc(WmsAgvDevice::getId));
        List<WmsAgvLog> logs = logService.lambdaQuery()
                .in(WmsAgvLog::getLogType, List.of("告警", "故障", "冲突"))
                .orderByDesc(WmsAgvLog::getId)
                .last("LIMIT 500")
                .list();
        Map<Long, String> codeMap = devices.stream()
                .collect(Collectors.toMap(WmsAgvDevice::getId, WmsAgvDevice::getAgvCode));

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            // 页签1：设备台账
            WriteSheet s0 = EasyExcel.writerSheet(0, "设备台账").head(List.of(
                    List.of("AGV编号"), List.of("名称"), List.of("最大载重(kg)"), List.of("速度(格/秒)"),
                    List.of("电量(%)"), List.of("运行状态"), List.of("当前坐标"), List.of("待命区"),
                    List.of("是否启用"), List.of("备注"))).build();
            List<List<Object>> deviceRows = new ArrayList<>();
            for (WmsAgvDevice d : devices) {
                deviceRows.add(List.of(
                        d.getAgvCode(), d.getAgvName(), d.getMaxLoad(), d.getSpeed(), d.getBattery(),
                        statusText(d.getStatus()), "(" + d.getPosX() + "," + d.getPosY() + ")",
                        "(" + d.getHomeX() + "," + d.getHomeY() + ")",
                        d.getEnabled() == 1 ? "启用" : "停用",
                        d.getRemark() == null ? "" : d.getRemark()));
            }
            writer.write(deviceRows, s0);

            // 页签2：告警记录
            WriteSheet s1 = EasyExcel.writerSheet(1, "告警记录").head(List.of(
                    List.of("时间"), List.of("AGV"), List.of("类型"), List.of("内容"))).build();
            List<List<Object>> logRows = new ArrayList<>();
            for (WmsAgvLog l : logs) {
                logRows.add(List.of(
                        l.getCreateTime() == null ? "" : l.getCreateTime().toString().replace("T", " "),
                        l.getAgvId() == null ? "系统" : codeMap.getOrDefault(l.getAgvId(), "AGV#" + l.getAgvId()),
                        l.getLogType(), l.getContent()));
            }
            writer.write(logRows, s1);
        }
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_IDLE -> "空闲";
            case STATUS_RUNNING -> "运行中";
            case STATUS_CHARGING -> "充电中";
            case STATUS_FAULT -> "故障";
            default -> "未知";
        };
    }
    @OperationLog("新增AGV设备")
    @RequireRole({"ADMIN", "MAINTENANCE"})
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
    @RequireRole({"ADMIN", "MAINTENANCE"})
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
    @RequireRole({"ADMIN", "MAINTENANCE"})
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
    @RequireRole({"ADMIN", "MAINTENANCE"})
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
    @RequireRole({"ADMIN", "MAINTENANCE"})
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
    @RequireRole({"ADMIN", "MAINTENANCE"})
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
