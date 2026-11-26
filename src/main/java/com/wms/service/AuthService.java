package com.wms.service;

import com.wms.dto.LoginDTO;
import com.wms.dto.RegisterDTO;
import com.wms.vo.LoginVO;
import com.wms.vo.MenuVO;

import java.util.List;
import java.util.Map;

/**
 * 认证服务：验证码、登录、注册、当前用户信息、角色与菜单
 */
public interface AuthService {

    /** 生成图形验证码，返回 {key, img(base64)} */
    Map<String, String> captcha();

    /** 登录：验证码校验 + 状态/锁定校验 + 密码校验 + 记录登录信息 + 签发 Token */
    LoginVO login(LoginDTO dto, String ip);

    /** 注册：唯一性校验 + BCrypt 加密 + 默认分配 OPERATOR 角色 */
    void register(RegisterDTO dto);

    /** 当前登录用户信息（含角色与菜单树） */
    LoginVO userinfo();

    /** 查询用户的角色编码列表 */
    List<String> listRoleCodes(Long userId);

    /** 按角色查询可见菜单树（ADMIN 返回全部） */
    List<MenuVO> listMenusByRoles(List<String> roles);
}
