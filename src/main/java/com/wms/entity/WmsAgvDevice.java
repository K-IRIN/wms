package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AGV设备档案表（wms_agv_device）
 */
@Data
@TableName("wms_agv_device")
public class WmsAgvDevice implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** AGV编号 */
    private String agvCode;

    /** AGV名称 */
    private String agvName;

    /** 最大载重(kg) */
    private BigDecimal maxLoad;

    /** 行驶速度(格/秒) */
    private BigDecimal speed;

    /** 电量(%) */
    private Integer battery;

    /** 运行状态：0空闲 1运行中 2充电中 3故障 */
    private Integer status;

    /** 是否启用：0停用 1启用 */
    private Integer enabled;

    /** 当前X坐标 */
    private Integer posX;

    /** 当前Y坐标 */
    private Integer posY;

    /** 待命区X坐标 */
    private Integer homeX;

    /** 待命区Y坐标 */
    private Integer homeY;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
