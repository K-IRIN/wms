package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsCheckOrder;
import com.wms.mapper.WmsCheckOrderMapper;
import com.wms.service.WmsCheckOrderService;
import org.springframework.stereotype.Service;

/**
 * 盘点单主表 ServiceImpl
 */
@Service
public class WmsCheckOrderServiceImpl extends ServiceImpl<WmsCheckOrderMapper, WmsCheckOrder> implements WmsCheckOrderService {
}
