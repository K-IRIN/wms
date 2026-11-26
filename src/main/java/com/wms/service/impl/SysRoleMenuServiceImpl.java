package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysRoleMenu;
import com.wms.mapper.SysRoleMenuMapper;
import com.wms.service.SysRoleMenuService;
import org.springframework.stereotype.Service;

/**
 * 角色菜单关联表 ServiceImpl
 */
@Service
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements SysRoleMenuService {
}
