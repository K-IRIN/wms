package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.wms.common.Result;
import com.wms.dto.LoginDTO;
import com.wms.dto.RegisterDTO;
import com.wms.service.AuthService;
import com.wms.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：验证码、登录、注册、当前用户信息、退出
 */
@Tag(name = "认证接口")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "获取图形验证码")
    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        return Result.ok(authService.captcha());
    }

    @Operation(summary = "账号密码登录")
    @OperationLog("账号密码登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Validated @RequestBody LoginDTO dto, HttpServletRequest request) {
        return Result.ok(authService.login(dto, request.getRemoteAddr()));
    }

    @Operation(summary = "用户注册（默认分配普通操作员角色）")
    @OperationLog("用户注册（默认分配普通操作员角色）")
    @PostMapping("/register")
    public Result<Void> register(@Validated @RequestBody RegisterDTO dto) {
        authService.register(dto);
        return Result.ok();
    }

    @Operation(summary = "获取当前登录用户信息（含角色与菜单树）")
    @GetMapping("/userinfo")
    public Result<LoginVO> userinfo() {
        return Result.ok(authService.userinfo());
    }

    @Operation(summary = "退出登录")
    @OperationLog("退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }
}
