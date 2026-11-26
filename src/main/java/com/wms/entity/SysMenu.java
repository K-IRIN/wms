package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜单权限表（sys_menu）
 */
@Data
@TableName("sys_menu")
public class SysMenu implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父菜单ID，0为顶级 */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 类型：M目录 C菜单 F按钮 */
    private String menuType;

    /** 前端路由地址 */
    private String path;

    /** 图标 */
    private String icon;

    /** 权限标识，如 wms:material:list */
    private String perms;

    /** 排序 */
    private Integer sort;

    /** 状态：0隐藏 1显示 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
