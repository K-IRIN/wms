package com.wms.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 物料分类树节点
 */
@Data
public class CategoryTreeVO {

    private Long id;
    private Long parentId;
    private String categoryName;
    private Integer sort;
    private Integer status;

    private List<CategoryTreeVO> children = new ArrayList<>();
}
