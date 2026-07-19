package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysUser;
import com.wms.mapper.SysUserMapper;
import com.wms.service.SysUserService;
import org.springframework.stereotype.Service;

/**
 * 用户表 ServiceImpl
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {
}
