package com.wms.vo;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点
 */
@Data
public class MenuVO {

    private Long id;
    private Long parentId;
    private String menuName;
    private String menuType;
    private String path;
    private String icon;
    private String perms;
    private Integer sort;

    private List<MenuVO> children;
}
