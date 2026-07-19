package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 仓库表（wms_warehouse）
 */
@Data
@TableName("wms_warehouse")
public class WmsWarehouse implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 仓库编号 */
    private String warehouseCode;

    /** 仓库名称 */
    private String warehouseName;

    /** 仓库地址 */
    private String address;

    /** 仓库类型：原料仓/半成品仓/成品仓/综合仓 */
    private String warehouseType;

    /** 负责人 */
    private String manager;

    /** 联系电话 */
    private String phone;

    /** 状态：0停用 1启用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
