package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsMaterial;
import com.wms.mapper.WmsMaterialMapper;
import com.wms.service.WmsMaterialService;
import org.springframework.stereotype.Service;

/**
 * 物料SKU表 ServiceImpl
 */
@Service
public class WmsMaterialServiceImpl extends ServiceImpl<WmsMaterialMapper, WmsMaterial> implements WmsMaterialService {
}
