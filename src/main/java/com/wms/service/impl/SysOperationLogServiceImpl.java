package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.SysOperationLog;
import com.wms.mapper.SysOperationLogMapper;
import com.wms.service.SysOperationLogService;
import org.springframework.stereotype.Service;

/**
 * 操作日志表 ServiceImpl
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog> implements SysOperationLogService {
}
