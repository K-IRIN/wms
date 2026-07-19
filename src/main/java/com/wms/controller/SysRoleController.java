package com.wms.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wms.common.Result;
import com.wms.entity.SysRole;
import com.wms.service.SysRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色接口
 */
@Tag(name = "角色接口")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService roleService;

    @Operation(summary = "启用角色列表（用户表单下拉用）")
    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.ok(roleService.list(
                Wrappers.<SysRole>lambdaQuery().eq(SysRole::getStatus, 1).orderByAsc(SysRole::getId)));
    }
}
