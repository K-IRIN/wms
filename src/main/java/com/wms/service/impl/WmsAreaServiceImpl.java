package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsArea;
import com.wms.mapper.WmsAreaMapper;
import com.wms.service.WmsAreaService;
import org.springframework.stereotype.Service;

/**
 * 库区表 ServiceImpl
 */
@Service
public class WmsAreaServiceImpl extends ServiceImpl<WmsAreaMapper, WmsArea> implements WmsAreaService {
}
