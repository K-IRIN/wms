package com.wms.controller;

import com.wms.annotation.OperationLog;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.StatusBatchDTO;
import com.wms.dto.UserSaveDTO;
import com.wms.entity.SysRole;
import com.wms.entity.SysUser;
import com.wms.entity.SysUserRole;
import com.wms.service.SysRoleService;
import com.wms.service.SysUserRoleService;
import com.wms.service.SysUserService;
import com.wms.utils.UserContext;
import com.wms.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户管理（仅管理员）：分页查询、新增、编辑、删除、批量启停、重置密码
 */
@Tag(name = "用户管理（管理员）")
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;
    private final SysUserRoleService userRoleService;
    private final SysRoleService roleService;

    /** 非管理员一律拒绝 */
    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BizException(403, "无权限操作，仅管理员可用");
        }
    }

    @Operation(summary = "用户分页查询")
    @GetMapping("/page")
    public Result<Page<UserVO>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Integer status) {
        requireAdmin();
        Page<SysUser> page = userService.page(new Page<>(current, size),
                Wrappers.<SysUser>lambdaQuery()
                        .and(StrUtil.isNotBlank(keyword), q -> q
                                .like(SysUser::getUsername, keyword)
                                .or().like(SysUser::getRealName, keyword)
                                .or().like(SysUser::getPhone, keyword))
                        .eq(status != null, SysUser::getStatus, status)
                        .orderByAsc(SysUser::getId));

        // 装配每个用户的角色名称
        Map<Long, String> roleNameMap = roleService.list().stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));
        Page<UserVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(u -> {
            UserVO vo = BeanUtil.copyProperties(u, UserVO.class);
            vo.setRoleNames(userRoleService.list(
                            Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, u.getId()))
                    .stream().map(ur -> roleNameMap.get(ur.getRoleId()))
                    .filter(Objects::nonNull).toList());
            return vo;
        }).toList());
        return Result.ok(voPage);
    }

    @Operation(summary = "新增用户（默认初始密码 123456）")
    @OperationLog("新增用户（默认初始密码 123456）")
    @PostMapping
    public Result<Void> add(@Validated @RequestBody UserSaveDTO dto) {
        requireAdmin();
        if (userService.count(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, dto.getUsername())) > 0) {
            throw new BizException("账号已存在");
        }
        SysUser user = BeanUtil.copyProperties(dto, SysUser.class);
        user.setId(null);
        user.setPassword(BCrypt.hashpw(StrUtil.isBlank(dto.getPassword()) ? "123456" : dto.getPassword()));
        user.setStatus(1);
        userService.save(user);
        saveUserRole(user.getId(), dto.getRoleId());
        return Result.ok();
    }

    @Operation(summary = "编辑用户")
    @OperationLog("编辑用户")
    @PutMapping
    public Result<Void> update(@Validated @RequestBody UserSaveDTO dto) {
        requireAdmin();
        if (dto.getId() == null) {
            throw new BizException("缺少用户ID");
        }
        SysUser user = userService.getById(dto.getId());
        if (user == null) {
            throw new BizException("用户不存在");
        }
        user.setRealName(dto.getRealName());
        user.setNickname(dto.getNickname());
        user.setDept(dto.getDept());
        user.setPhone(dto.getPhone());
        userService.updateById(user);

        // 重新分配角色
        userRoleService.remove(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, user.getId()));
        saveUserRole(user.getId(), dto.getRoleId());
        return Result.ok();
    }

    @Operation(summary = "删除（注销）用户")
    @OperationLog("删除（注销）用户")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireAdmin();
        if (id.equals(UserContext.getUserId())) {
            throw new BizException("不能删除当前登录账号");
        }
        userService.removeById(id);
        userRoleService.remove(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, id));
        return Result.ok();
    }

    @Operation(summary = "批量启用/禁用账号")
    @OperationLog("批量启用/禁用账号")
    @PutMapping("/status")
    public Result<Void> status(@Validated @RequestBody StatusBatchDTO dto) {
        requireAdmin();
        if (dto.getStatus() == 0 && dto.getIds().contains(UserContext.getUserId())) {
            throw new BizException("不能禁用当前登录账号");
        }
        List<SysUser> users = userService.listByIds(dto.getIds());
        users.forEach(u -> u.setStatus(dto.getStatus()));
        userService.updateBatchById(users);
        return Result.ok();
    }

    @Operation(summary = "重置密码为 123456")
    @OperationLog("重置密码为 123456")
    @PutMapping("/resetPwd/{id}")
    public Result<Void> resetPwd(@PathVariable Long id) {
        requireAdmin();
        SysUser user = userService.getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        user.setPassword(BCrypt.hashpw("123456"));
        userService.updateById(user);
        return Result.ok();
    }

    private void saveUserRole(Long userId, Long roleId) {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleService.save(ur);
    }
}
