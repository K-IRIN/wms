package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.wms.annotation.RequireRole;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.service.AgvEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AGV 异常模拟（v2.0）：运维员手动注入故障，验证系统自动恢复能力。
 *
 * <p>权限：仅"管理员"和"设备运维员"可注入/修复，由 {@link RequireRole} 注解 + AOP 切面统一拦截，
 * 未授权返回 403（原先是本类内手写的 checkRole()，现已统一到注解 + 切面，语义更一致）。
 */
@Tag(name = "AGV异常模拟")
@RestController
@RequestMapping("/agv/fault")
@RequiredArgsConstructor
@RequireRole({"ADMIN", "MAINTENANCE"})
public class AgvFaultController {

    private final AgvEngineService engine;

    @Operation(summary = "注入异常：fault故障停机 / lowBattery电量不足 / block通道堵塞 / pickFail取货失败")
    @OperationLog("注入AGV异常")
    @PutMapping("/inject/{agvId}/{type}")
    public Result<Void> inject(@PathVariable Long agvId, @PathVariable String type,
                               @RequestParam(required = false) Integer x,
                               @RequestParam(required = false) Integer y) {
        try {
            switch (type) {
                case "fault" -> engine.injectFault(agvId);
                case "lowBattery" -> engine.injectLowBattery(agvId);
                // block 默认封锁该车当前格；?x=&y= 可封锁指定格子（阶段 4.4 验收用）
                case "block" -> engine.injectBlock(agvId, x, y);
                case "pickFail" -> engine.injectPickFail(agvId);
                default -> throw new BizException("不支持的异常类型：" + type);
            }
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
        return Result.ok();
    }

    @Operation(summary = "多车协同快照：占用表/路径队列/阻塞格/等待列表（验收用）")
    @GetMapping("/grid")
    public Result<Map<String, Object>> grid() {
        return Result.ok(engine.gridSnapshot());
    }

    @Operation(summary = "模拟行驶耗电（只改电量，验证在途电量兜底）")
    @OperationLog("模拟AGV耗电")
    @PutMapping("/drain/{agvId}/{battery}")
    public Result<Void> drain(@PathVariable Long agvId, @PathVariable Integer battery) {
        engine.drainBattery(agvId, battery);
        return Result.ok();
    }

    @Operation(summary = "故障修复")
    @OperationLog("修复AGV故障")
    @PutMapping("/repair/{agvId}")
    public Result<Void> repair(@PathVariable Long agvId) {
        try {
            engine.repair(agvId);
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
        return Result.ok();
    }
}
