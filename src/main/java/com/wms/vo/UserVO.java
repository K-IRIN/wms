package com.wms.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户信息视图（不暴露密码）
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String realName;
    private String dept;
    private String phone;
    private String avatar;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private LocalDateTime createTime;

    /** 角色编码列表，如 ["ADMIN"] */
    private List<String> roles;

    /** 角色名称列表（用户管理页展示用） */
    private List<String> roleNames;
}
