package com.wms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.dto.DocCancelDTO;
import com.wms.entity.WmsCheckOrder;
import com.wms.entity.WmsCustomer;
import com.wms.entity.WmsInboundOrder;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsMoveOrder;
import com.wms.entity.WmsOutboundOrder;
import com.wms.entity.WmsSupplier;
import com.wms.entity.WmsWarehouse;
import com.wms.utils.UserContext;
import com.wms.vo.DocOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 单据中心：入库/出库/移库/盘点四类单据统一查询与作废
 */
@Service
@RequiredArgsConstructor
public class DocService {

    private final WmsInboundOrderService inboundOrderService;
    private final WmsOutboundOrderService outboundOrderService;
    private final WmsMoveOrderService moveOrderService;
    private final WmsCheckOrderService checkOrderService;
    private final WmsSupplierService supplierService;
    private final WmsCustomerService customerService;
    private final WmsWarehouseService warehouseService;
    private final WmsMaterialService materialService;
    private final WmsLocationService locationService;

    /** 各类型单据状态文案 */
    private static final Map<String, Map<Integer, String>> STATUS_NAMES = Map.of(
            "INBOUND", Map.of(0, "待审核", 1, "已审核", 2, "已驳回", 3, "已完成"),
            "OUTBOUND", Map.of(0, "待审核", 1, "已审核", 2, "已驳回", 3, "已完成"),
            "MOVE", Map.of(0, "待确认", 1, "已完成"),
            "CHECK", Map.of(0, "待盘点", 1, "待审核", 3, "已完成"));

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "INBOUND", "采购入库单",
            "OUTBOUND", "销售出库单",
            "MOVE", "移库单",
            "CHECK", "盘点单");

    /**
     * 统一分页查询
     */
    public Page<DocOrderVO> page(String type, long pageNum, long pageSize, String orderNo, Integer status) {
        String t = checkType(type);
        return switch (t) {
            case "INBOUND" -> {
                Page<WmsInboundOrder> p = inboundOrderService.lambdaQuery()
                        .like(StrUtil.isNotBlank(orderNo), WmsInboundOrder::getOrderNo, orderNo)
                        .eq(status != null, WmsInboundOrder::getStatus, status)
                        .orderByDesc(WmsInboundOrder::getId)
                        .page(new Page<>(pageNum, pageSize));
                yield convert(p, this::toInboundVO);
            }
            case "OUTBOUND" -> {
                Page<WmsOutboundOrder> p = outboundOrderService.lambdaQuery()
                        .like(StrUtil.isNotBlank(orderNo), WmsOutboundOrder::getOrderNo, orderNo)
                        .eq(status != null, WmsOutboundOrder::getStatus, status)
                        .orderByDesc(WmsOutboundOrder::getId)
                        .page(new Page<>(pageNum, pageSize));
                yield convert(p, this::toOutboundVO);
            }
            case "MOVE" -> {
                Page<WmsMoveOrder> p = moveOrderService.lambdaQuery()
                        .like(StrUtil.isNotBlank(orderNo), WmsMoveOrder::getOrderNo, orderNo)
                        .eq(status != null, WmsMoveOrder::getStatus, status)
                        .orderByDesc(WmsMoveOrder::getId)
                        .page(new Page<>(pageNum, pageSize));
                yield convert(p, this::toMoveVO);
            }
            default -> {
                Page<WmsCheckOrder> p = checkOrderService.lambdaQuery()
                        .like(StrUtil.isNotBlank(orderNo), WmsCheckOrder::getOrderNo, orderNo)
                        .eq(status != null, WmsCheckOrder::getStatus, status)
                        .orderByDesc(WmsCheckOrder::getId)
                        .page(new Page<>(pageNum, pageSize));
                yield convert(p, this::toCheckVO);
            }
        };
    }

    /**
     * 单据作废：必填原因，作废后 invalidFlag=1 只读。
     * 仅允许作废未影响库存的单据（入库/出库：待审核、已驳回；移库：待确认；盘点：待盘点、待审核）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(DocCancelDTO dto) {
        String t = checkType(dto.getType());
        String operator = UserContext.get() == null ? "system" : UserContext.get().nickname();
        switch (t) {
            case "INBOUND" -> {
                WmsInboundOrder order = requireInbound(dto.getId());
                checkInvalid(order.getInvalidFlag());
                if (order.getStatus() != 0 && order.getStatus() != 2) {
                    throw new BizException("仅待审核或已驳回的入库单可作废，该单当前状态不允许作废");
                }
                inboundOrderService.lambdaUpdate()
                        .eq(WmsInboundOrder::getId, order.getId())
                        .set(WmsInboundOrder::getInvalidFlag, 1)
                        .set(WmsInboundOrder::getInvalidReason, dto.getReason() + "（操作人：" + operator + "）")
                        .update();
            }
            case "OUTBOUND" -> {
                WmsOutboundOrder order = outboundOrderService.getById(dto.getId());
                if (order == null) {
                    throw new BizException("出库单不存在");
                }
                checkInvalid(order.getInvalidFlag());
                if (order.getStatus() != 0 && order.getStatus() != 2) {
                    throw new BizException("仅待审核或已驳回的出库单可作废，该单当前状态不允许作废");
                }
                outboundOrderService.lambdaUpdate()
                        .eq(WmsOutboundOrder::getId, order.getId())
                        .set(WmsOutboundOrder::getInvalidFlag, 1)
                        .set(WmsOutboundOrder::getInvalidReason, dto.getReason() + "（操作人：" + operator + "）")
                        .update();
            }
            case "MOVE" -> {
                WmsMoveOrder order = moveOrderService.getById(dto.getId());
                if (order == null) {
                    throw new BizException("移库单不存在");
                }
                checkInvalid(order.getInvalidFlag());
                if (order.getStatus() != 0) {
                    throw new BizException("移库创建即生效（库存已转移），不可作废；如需撤销请做一笔反向移库");
                }
                moveOrderService.lambdaUpdate()
                        .eq(WmsMoveOrder::getId, order.getId())
                        .set(WmsMoveOrder::getInvalidFlag, 1)
                        .set(WmsMoveOrder::getInvalidReason, dto.getReason() + "（操作人：" + operator + "）")
                        .update();
            }
            default -> {
                WmsCheckOrder order = checkOrderService.getById(dto.getId());
                if (order == null) {
                    throw new BizException("盘点单不存在");
                }
                checkInvalid(order.getInvalidFlag());
                if (order.getStatus() != 0 && order.getStatus() != 1) {
                    throw new BizException("仅待盘点或待审核的盘点单可作废，该单当前状态不允许作废");
                }
                checkOrderService.lambdaUpdate()
                        .eq(WmsCheckOrder::getId, order.getId())
                        .set(WmsCheckOrder::getInvalidFlag, 1)
                        .set(WmsCheckOrder::getInvalidReason, dto.getReason() + "（操作人：" + operator + "）")
                        .update();
            }
        }
    }

    private void checkInvalid(Integer invalidFlag) {
        if (invalidFlag != null && invalidFlag == 1) {
            throw new BizException("该单据已作废，请勿重复操作");
        }
    }

    private WmsInboundOrder requireInbound(Long id) {
        WmsInboundOrder order = inboundOrderService.getById(id);
        if (order == null) {
            throw new BizException("入库单不存在");
        }
        return order;
    }

    private String checkType(String type) {
        String t = type == null ? "" : type.toUpperCase();
        if (!TYPE_NAMES.containsKey(t)) {
            throw new BizException("不支持的单据类型：" + type);
        }
        return t;
    }

    private <T> Page<DocOrderVO> convert(Page<T> page, Function<T, DocOrderVO> mapper) {
        Page<DocOrderVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(mapper).collect(Collectors.toList()));
        return result;
    }

    private DocOrderVO baseVO(Long id, String type, String orderNo, Integer status,
                              String operator, Integer invalidFlag, String invalidReason,
                              String remark, java.time.LocalDateTime createTime) {
        DocOrderVO vo = new DocOrderVO();
        vo.setId(id);
        vo.setOrderType(type);
        vo.setOrderTypeName(TYPE_NAMES.get(type));
        vo.setOrderNo(orderNo);
        vo.setStatus(status);
        vo.setStatusName(STATUS_NAMES.get(type).getOrDefault(status, "未知"));
        vo.setOperator(operator);
        vo.setInvalidFlag(invalidFlag == null ? 0 : invalidFlag);
        vo.setInvalidReason(invalidReason);
        vo.setRemark(remark);
        vo.setCreateTime(createTime);
        return vo;
    }

    private DocOrderVO toInboundVO(WmsInboundOrder o) {
        DocOrderVO vo = baseVO(o.getId(), "INBOUND", o.getOrderNo(), o.getStatus(),
                o.getApplicant(), o.getInvalidFlag(), o.getInvalidReason(), o.getRemark(), o.getCreateTime());
        vo.setBizInfo("供应商：" + supplierName(o.getSupplierId()) + " / 仓库：" + warehouseName(o.getWarehouseId()));
        return vo;
    }

    private DocOrderVO toOutboundVO(WmsOutboundOrder o) {
        DocOrderVO vo = baseVO(o.getId(), "OUTBOUND", o.getOrderNo(), o.getStatus(),
                o.getApplicant(), o.getInvalidFlag(), o.getInvalidReason(), o.getRemark(), o.getCreateTime());
        vo.setBizInfo("客户：" + customerName(o.getCustomerId()) + " / 仓库：" + warehouseName(o.getWarehouseId()));
        return vo;
    }

    private DocOrderVO toMoveVO(WmsMoveOrder o) {
        DocOrderVO vo = baseVO(o.getId(), "MOVE", o.getOrderNo(), o.getStatus(),
                o.getOperator(), o.getInvalidFlag(), o.getInvalidReason(), o.getRemark(), o.getCreateTime());
        vo.setBizInfo("物料：" + materialCode(o.getMaterialId())
                + " / " + locationCode(o.getFromLocationId()) + " → " + locationCode(o.getToLocationId())
                + " × " + o.getQuantity());
        return vo;
    }

    private DocOrderVO toCheckVO(WmsCheckOrder o) {
        DocOrderVO vo = baseVO(o.getId(), "CHECK", o.getOrderNo(), o.getStatus(),
                o.getChecker(), o.getInvalidFlag(), o.getInvalidReason(), o.getRemark(), o.getCreateTime());
        String scope = switch (o.getScopeType() == null ? 1 : o.getScopeType()) {
            case 2 -> "按库区";
            case 3 -> "按分类";
            default -> "按仓库";
        };
        vo.setBizInfo("仓库：" + warehouseName(o.getWarehouseId()) + " / " + scope
                + (StrUtil.isNotBlank(o.getCheckPeriod()) ? " / " + o.getCheckPeriod() : ""));
        return vo;
    }

    private String warehouseName(Long id) {
        if (id == null) {
            return "-";
        }
        WmsWarehouse w = warehouseService.getById(id);
        return w == null ? "-" : w.getWarehouseName();
    }

    private String materialCode(Long id) {
        if (id == null) {
            return "-";
        }
        WmsMaterial m = materialService.getById(id);
        return m == null ? "-" : m.getMaterialCode();
    }

    private String locationCode(Long id) {
        if (id == null) {
            return "-";
        }
        WmsLocation l = locationService.getById(id);
        return l == null ? "-" : l.getLocationCode();
    }

    private String supplierName(Long id) {
        if (id == null) {
            return "-";
        }
        WmsSupplier s = supplierService.getById(id);
        return s == null ? "-" : s.getSupplierName();
    }

    private String customerName(Long id) {
        if (id == null) {
            return "-";
        }
        WmsCustomer c = customerService.getById(id);
        return c == null ? "-" : c.getCustomerName();
    }
}
