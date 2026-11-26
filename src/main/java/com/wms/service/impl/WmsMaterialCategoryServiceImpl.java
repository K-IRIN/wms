package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsMaterialCategory;
import com.wms.mapper.WmsMaterialCategoryMapper;
import com.wms.service.WmsMaterialCategoryService;
import org.springframework.stereotype.Service;

/**
 * 物料分类表 ServiceImpl
 */
@Service
public class WmsMaterialCategoryServiceImpl extends ServiceImpl<WmsMaterialCategoryMapper, WmsMaterialCategory> implements WmsMaterialCategoryService {
}
