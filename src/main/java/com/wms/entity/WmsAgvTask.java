package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AGV搬运任务表（wms_agv_task）
 */
@Data
@TableName("wms_agv_task")
public class WmsAgvTask implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号 */
    private String taskNo;

    /** 任务类型：入库搬运/出库搬运/移库搬运 */
    private String taskType;

    /** 关联WMS单据号 */
    private String orderNo;

    /** 搬运物料ID */
    private Long materialId;

    /** 起点库位ID */
    private Long fromLocationId;

    /** 终点库位ID */
    private Long toLocationId;

    /** 优先级：0普通 1紧急 */
    private Integer priority;

    /** 状态：0待分配 1前往取货 2取货中 3前往卸货 4卸货中 5返回待命区 6已完成 7已取消 */
    private Integer status;

    /** 执行AGV的ID */
    private Long agvId;

    /** 异常信息 */
    private String errorMsg;

    /** 开始执行时间 */
    private LocalDateTime startTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
