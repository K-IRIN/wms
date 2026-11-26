package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsInboundItem;
import com.wms.mapper.WmsInboundItemMapper;
import com.wms.service.WmsInboundItemService;
import org.springframework.stereotype.Service;

/**
 * 入库单明细表 ServiceImpl
 */
@Service
public class WmsInboundItemServiceImpl extends ServiceImpl<WmsInboundItemMapper, WmsInboundItem> implements WmsInboundItemService {
}
