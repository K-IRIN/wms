package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志表（sys_operation_log）
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人ID */
    private Long userId;

    /** 操作人账号 */
    private String username;

    /** 操作内容 */
    private String operation;

    /** 请求方式与路径 */
    private String method;

    /** 操作IP */
    private String ip;

    /** 请求参数 */
    private String params;

    /** 操作时间 */
    private LocalDateTime createTime;
}
