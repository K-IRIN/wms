package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsOutboundOrder;
import com.wms.mapper.WmsOutboundOrderMapper;
import com.wms.service.WmsOutboundOrderService;
import org.springframework.stereotype.Service;

/**
 * 出库单主表 ServiceImpl
 */
@Service
public class WmsOutboundOrderServiceImpl extends ServiceImpl<WmsOutboundOrderMapper, WmsOutboundOrder> implements WmsOutboundOrderService {
}
