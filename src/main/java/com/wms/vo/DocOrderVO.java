package com.wms.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单据中心统一视图（入库/出库/移库/盘点四类单据的公共字段）
 */
@Data
public class DocOrderVO {

    /** 单据ID */
    private Long id;

    /** 单据类型：INBOUND入库 OUTBOUND出库 MOVE移库 CHECK盘点 */
    private String orderType;

    /** 单据类型名称 */
    private String orderTypeName;

    /** 单号 */
    private String orderNo;

    /** 单据状态码 */
    private Integer status;

    /** 状态名称 */
    private String statusName;

    /** 业务摘要（如"供应商：XX / 仓库：主仓库"） */
    private String bizInfo;

    /** 制单人 */
    private String operator;

    /** 作废标记：0正常 1已作废 */
    private Integer invalidFlag;

    /** 作废原因 */
    private String invalidReason;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;
}
