package com.wms.controller;

import com.wms.common.Result;
import com.wms.service.AgvEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 调度引擎运行指标（阶段 9）。
 *
 * <p>tick 耗时与推送队列指标由 {@link AgvEngineService#metrics()} 内存统计，
 * 验收脚本据此断言 tick p99 与丢帧数；只读接口，登录即可访问。
 */
@Tag(name = "AGV 调度引擎")
@RestController
@RequestMapping("/agv/engine")
@RequiredArgsConstructor
public class AgvEngineController {

    private final AgvEngineService agvEngineService;

    @Operation(summary = "调度引擎运行指标")
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        return Result.ok(agvEngineService.metrics());
    }
}
