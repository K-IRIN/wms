package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysUserRole;
import com.wms.mapper.SysUserRoleMapper;
import com.wms.service.SysUserRoleService;
import org.springframework.stereotype.Service;

/**
 * 用户角色关联表 ServiceImpl
 */
@Service
public class SysUserRoleServiceImpl extends ServiceImpl<SysUserRoleMapper, SysUserRole> implements SysUserRoleService {
}
