package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库位表（wms_location）
 */
@Data
@TableName("wms_location")
public class WmsLocation implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属库区ID */
    private Long areaId;

    /** 库位编号 */
    private String locationCode;

    /** 库位容量 */
    private BigDecimal capacity;

    /** 可存放物料类型 */
    private String goodsType;

    /** 状态：0空闲 1占用 2禁用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
