package com.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物料分类表（wms_material_category）
 */
@Data
@TableName("wms_material_category")
public class WmsMaterialCategory implements Serializable {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父分类ID，0为顶级 */
    private Long parentId;

    /** 分类名称 */
    private String categoryName;

    /** 排序 */
    private Integer sort;

    /** 状态：0停用 1启用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
