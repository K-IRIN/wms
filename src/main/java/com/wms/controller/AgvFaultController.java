package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.service.AgvEngineService;
import com.wms.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AGV 异常模拟（v2.0）：运维员手动注入故障，验证系统自动恢复能力
 * 仅"管理员"和"设备运维员"可操作
 */
@Tag(name = "AGV异常模拟")
@RestController
@RequestMapping("/agv/fault")
@RequiredArgsConstructor
public class AgvFaultController {

    private final AgvEngineService engine;

    @Operation(summary = "注入异常：fault故障停机 / lowBattery电量不足 / block通道堵塞 / pickFail取货失败")
    @OperationLog("注入AGV异常")
    @PutMapping("/inject/{agvId}/{type}")
    public Result<Void> inject(@PathVariable Long agvId, @PathVariable String type) {
        checkRole();
        try {
            switch (type) {
                case "fault" -> engine.injectFault(agvId);
                case "lowBattery" -> engine.injectLowBattery(agvId);
                case "block" -> engine.injectBlock(agvId);
                case "pickFail" -> engine.injectPickFail(agvId);
                default -> throw new BizException("不支持的异常类型：" + type);
            }
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
        return Result.ok();
    }

    @Operation(summary = "故障修复")
    @OperationLog("修复AGV故障")
    @PutMapping("/repair/{agvId}")
    public Result<Void> repair(@PathVariable Long agvId) {
        checkRole();
        try {
            engine.repair(agvId);
        } catch (IllegalStateException e) {
            throw new BizException(e.getMessage());
        }
        return Result.ok();
    }

    /** 仅管理员或设备运维员可注入/修复 */
    private void checkRole() {
        List<String> roles = UserContext.get().roles();
        if (roles == null || (!roles.contains("ADMIN") && !roles.contains("MAINTENANCE"))) {
            throw new BizException(403, "仅管理员或设备运维员可执行异常模拟操作");
        }
    }
}
