package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysRole;
import com.wms.mapper.SysRoleMapper;
import com.wms.service.SysRoleService;
import org.springframework.stereotype.Service;

/**
 * 角色表 ServiceImpl
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {
}
