package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsLocation;
import com.wms.mapper.WmsLocationMapper;
import com.wms.service.WmsLocationService;
import org.springframework.stereotype.Service;

/**
 * 库位表 ServiceImpl
 */
@Service
public class WmsLocationServiceImpl extends ServiceImpl<WmsLocationMapper, WmsLocation> implements WmsLocationService {
}
