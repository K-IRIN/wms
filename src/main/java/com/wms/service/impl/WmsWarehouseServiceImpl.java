package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsWarehouse;
import com.wms.mapper.WmsWarehouseMapper;
import com.wms.service.WmsWarehouseService;
import org.springframework.stereotype.Service;

/**
 * 仓库表 ServiceImpl
 */
@Service
public class WmsWarehouseServiceImpl extends ServiceImpl<WmsWarehouseMapper, WmsWarehouse> implements WmsWarehouseService {
}
