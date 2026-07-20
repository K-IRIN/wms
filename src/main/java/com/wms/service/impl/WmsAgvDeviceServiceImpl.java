package com.wms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wms.entity.WmsAgvDevice;
import com.wms.mapper.WmsAgvDeviceMapper;
import com.wms.service.WmsAgvDeviceService;
import org.springframework.stereotype.Service;

/**
 * AGV设备档案表 ServiceImpl
 */
@Service
public class WmsAgvDeviceServiceImpl extends ServiceImpl<WmsAgvDeviceMapper, WmsAgvDevice> implements WmsAgvDeviceService {
}
