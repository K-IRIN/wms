package com.wms.controller;

import com.wms.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 骨架连通性验证接口（临时，后续模块开发完成后可删除）
 */
@Tag(name = "测试接口")
@RestController
public class TestController {

    @Operation(summary = "连通性测试")
    @GetMapping("/test/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
