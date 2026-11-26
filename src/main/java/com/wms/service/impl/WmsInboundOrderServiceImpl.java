package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsInboundOrder;
import com.wms.mapper.WmsInboundOrderMapper;
import com.wms.service.WmsInboundOrderService;
import org.springframework.stereotype.Service;

/**
 * 入库单主表 ServiceImpl
 */
@Service
public class WmsInboundOrderServiceImpl extends ServiceImpl<WmsInboundOrderMapper, WmsInboundOrder> implements WmsInboundOrderService {
}
