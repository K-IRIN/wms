package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsInventory;
import com.wms.mapper.WmsInventoryMapper;
import com.wms.service.WmsInventoryService;
import org.springframework.stereotype.Service;

/**
 * 库存表 ServiceImpl
 */
@Service
public class WmsInventoryServiceImpl extends ServiceImpl<WmsInventoryMapper, WmsInventory> implements WmsInventoryService {
}
