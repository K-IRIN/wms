package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表（sys_user）
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号 */
    private String username;

    /** 密码（BCrypt加密） */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 姓名 */
    private String realName;

    /** 部门 */
    private String dept;

    /** 手机号 */
    private String phone;

    /** 头像URL */
    private String avatar;

    /** 状态：0禁用 1启用 2锁定 */
    private Integer status;

    /** 连续登录失败次数 */
    private Integer loginFailCount;

    /** 锁定时间 */
    private LocalDateTime lockTime;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
