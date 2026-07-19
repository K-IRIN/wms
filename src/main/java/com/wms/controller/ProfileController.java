package com.wms.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.UpdatePwdDTO;
import com.wms.entity.SysOperationLog;
import com.wms.entity.SysUser;
import com.wms.service.SysOperationLogService;
import com.wms.service.SysUserService;
import com.wms.utils.UserContext;
import com.wms.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * 个人中心：个人信息、头像、修改密码（敏感操作留痕）
 */
@Tag(name = "个人中心")
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final SysUserService userService;
    private final SysOperationLogService operationLogService;

    @Operation(summary = "查询个人信息")
    @GetMapping("/info")
    public Result<UserVO> info() {
        SysUser user = userService.getById(UserContext.getUserId());
        return Result.ok(BeanUtil.copyProperties(user, UserVO.class));
    }

    @Operation(summary = "修改个人信息（昵称/电话）")
    @PutMapping("/info")
    public Result<Void> updateInfo(@RequestBody SysUser dto) {
        SysUser user = userService.getById(UserContext.getUserId());
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        userService.updateById(user);
        return Result.ok();
    }

    @Operation(summary = "上传头像")
    @PostMapping("/avatar")
    public Result<String> avatar(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        String original = file.getOriginalFilename();
        String ext = (original != null && original.contains("."))
                ? original.substring(original.lastIndexOf('.')).toLowerCase() : ".png";
        if (!Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp").contains(ext)) {
            throw new BizException("仅支持 png/jpg/jpeg/gif/webp 图片");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new BizException("图片大小不能超过 2MB");
        }
        String dir = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "avatar";
        cn.hutool.core.io.FileUtil.mkdir(dir);
        String fileName = IdUtil.simpleUUID() + ext;
        file.transferTo(new File(dir, fileName));

        String url = "/uploads/avatar/" + fileName;
        SysUser user = userService.getById(UserContext.getUserId());
        user.setAvatar(url);
        userService.updateById(user);
        return Result.ok(url);
    }

    @Operation(summary = "修改密码（需校验原密码，操作留痕）")
    @PutMapping("/password")
    public Result<Void> updatePassword(@Validated @RequestBody UpdatePwdDTO dto, HttpServletRequest request) {
        SysUser user = userService.getById(UserContext.getUserId());
        if (!BCrypt.checkpw(dto.getOldPassword(), user.getPassword())) {
            throw new BizException("原密码错误");
        }
        user.setPassword(BCrypt.hashpw(dto.getNewPassword()));
        userService.updateById(user);

        // 敏感操作留痕
        SysOperationLog opLog = new SysOperationLog();
        opLog.setUserId(user.getId());
        opLog.setUsername(user.getUsername());
        opLog.setOperation("修改个人密码");
        opLog.setMethod("PUT /profile/password");
        opLog.setIp(request.getRemoteAddr());
        operationLogService.save(opLog);
        return Result.ok();
    }
}
