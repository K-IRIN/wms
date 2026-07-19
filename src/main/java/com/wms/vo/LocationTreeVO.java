package com.wms.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 库区库位树节点（仓库 → 库区 → 库位 三级）
 */
@Data
public class LocationTreeVO {

    /** 节点ID（各层自己的主键） */
    private Long id;
    /** 节点类型：warehouse / area / location */
    private String nodeType;
    /** 显示名称 */
    private String label;
    /** 编号 */
    private String code;
    /** 状态（含义随类型不同） */
    private Integer status;
    /** 所属仓库ID（库区、库位节点用） */
    private Long warehouseId;
    /** 所属库区ID（库位节点用） */
    private Long areaId;
    /** 库位容量（库位节点用） */
    private BigDecimal capacity;
    /** 可存放物料类型（库位节点用） */
    private String goodsType;
    /** 备注 */
    private String remark;

    private List<LocationTreeVO> children = new ArrayList<>();
}
