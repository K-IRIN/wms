package com.wms.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录成功返回：Token + 用户信息 + 角色 + 菜单树
 */
@Data
public class LoginVO {

    private String token;

    private UserVO user;

    /** 角色编码列表 */
    private List<String> roles;

    /** 当前用户可见的菜单树 */
    private List<MenuVO> menus;
}
