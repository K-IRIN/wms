package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsSupplier;
import com.wms.mapper.WmsSupplierMapper;
import com.wms.service.WmsSupplierService;
import org.springframework.stereotype.Service;

/**
 * 供应商表 ServiceImpl
 */
@Service
public class WmsSupplierServiceImpl extends ServiceImpl<WmsSupplierMapper, WmsSupplier> implements WmsSupplierService {
}
