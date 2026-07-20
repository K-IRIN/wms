package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AGV运行与告警日志表（wms_agv_log）
 */
@Data
@TableName("wms_agv_log")
public class WmsAgvLog implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** AGV的ID */
    private Long agvId;

    /** 关联任务ID */
    private Long taskId;

    /** 日志类型：状态变更/告警/故障/充电 */
    private String logType;

    /** 日志内容 */
    private String content;

    /** 创建时间 */
    private LocalDateTime createTime;
}
