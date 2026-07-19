package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsCustomer;
import com.wms.mapper.WmsCustomerMapper;
import com.wms.service.WmsCustomerService;
import org.springframework.stereotype.Service;

/**
 * 客户表 ServiceImpl
 */
@Service
public class WmsCustomerServiceImpl extends ServiceImpl<WmsCustomerMapper, WmsCustomer> implements WmsCustomerService {
}
