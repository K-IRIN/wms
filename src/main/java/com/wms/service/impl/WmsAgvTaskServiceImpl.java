package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsAgvTask;
import com.wms.mapper.WmsAgvTaskMapper;
import com.wms.service.WmsAgvTaskService;
import org.springframework.stereotype.Service;

/**
 * AGV搬运任务表 ServiceImpl
 */
@Service
public class WmsAgvTaskServiceImpl extends ServiceImpl<WmsAgvTaskMapper, WmsAgvTask> implements WmsAgvTaskService {
}
