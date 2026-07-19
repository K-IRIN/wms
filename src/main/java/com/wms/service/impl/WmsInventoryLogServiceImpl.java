package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsInventoryLog;
import com.wms.mapper.WmsInventoryLogMapper;
import com.wms.service.WmsInventoryLogService;
import org.springframework.stereotype.Service;

/**
 * 库存流水表 ServiceImpl
 */
@Service
public class WmsInventoryLogServiceImpl extends ServiceImpl<WmsInventoryLogMapper, WmsInventoryLog> implements WmsInventoryLogService {
}
