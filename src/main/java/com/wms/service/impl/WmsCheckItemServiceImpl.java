package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsCheckItem;
import com.wms.mapper.WmsCheckItemMapper;
import com.wms.service.WmsCheckItemService;
import org.springframework.stereotype.Service;

/**
 * 盘点单明细表 ServiceImpl
 */
@Service
public class WmsCheckItemServiceImpl extends ServiceImpl<WmsCheckItemMapper, WmsCheckItem> implements WmsCheckItemService {
}
