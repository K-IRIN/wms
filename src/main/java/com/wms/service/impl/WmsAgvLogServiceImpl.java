package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsAgvLog;
import com.wms.mapper.WmsAgvLogMapper;
import com.wms.service.WmsAgvLogService;
import org.springframework.stereotype.Service;

/**
 * AGV运行日志表 ServiceImpl
 */
@Service
public class WmsAgvLogServiceImpl extends ServiceImpl<WmsAgvLogMapper, WmsAgvLog> implements WmsAgvLogService {
}
