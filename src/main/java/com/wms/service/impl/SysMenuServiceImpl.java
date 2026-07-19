package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysMenu;
import com.wms.mapper.SysMenuMapper;
import com.wms.service.SysMenuService;
import org.springframework.stereotype.Service;

/**
 * 菜单权限表 ServiceImpl
 */
@Service
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {
}
