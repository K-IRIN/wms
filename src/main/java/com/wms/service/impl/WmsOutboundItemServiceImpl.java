package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsOutboundItem;
import com.wms.mapper.WmsOutboundItemMapper;
import com.wms.service.WmsOutboundItemService;
import org.springframework.stereotype.Service;

/**
 * 出库单明细表 ServiceImpl
 */
@Service
public class WmsOutboundItemServiceImpl extends ServiceImpl<WmsOutboundItemMapper, WmsOutboundItem> implements WmsOutboundItemService {
}
