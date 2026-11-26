package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsMoveOrder;
import com.wms.mapper.WmsMoveOrderMapper;
import com.wms.service.WmsMoveOrderService;
import org.springframework.stereotype.Service;

/**
 * 移库单表 ServiceImpl
 */
@Service
public class WmsMoveOrderServiceImpl extends ServiceImpl<WmsMoveOrderMapper, WmsMoveOrder> implements WmsMoveOrderService {
}
