package com.wms.controller;

import com.wms.annotation.OperationLog;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.AuditDTO;
import com.wms.dto.OutboundCreateDTO;
import com.wms.dto.PickDTO;
import com.wms.dto.excel.OutboundOrderExcel;
import com.wms.entity.WmsCustomer;
import com.wms.entity.WmsOutboundOrder;
import com.wms.entity.WmsWarehouse;
import com.wms.service.OutboundService;
import com.wms.service.WmsCustomerService;
import com.wms.service.WmsOutboundOrderService;
import com.wms.service.WmsWarehouseService;
import com.wms.vo.OutboundOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 销售出库管理
 */
@Tag(name = "销售出库管理")
@RestController
@RequestMapping("/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;
    private final WmsOutboundOrderService orderService;
    private final WmsCustomerService customerService;
    private final WmsWarehouseService warehouseService;

    @Operation(summary = "出库单分页查询")
    @GetMapping("/page")
    public Result<Page<WmsOutboundOrder>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String orderNo,
                                               @RequestParam(required = false) Long customerId,
                                               @RequestParam(required = false) Integer status,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd) {
        Page<WmsOutboundOrder> page = orderService.lambdaQuery()
                .like(StrUtil.isNotBlank(orderNo), WmsOutboundOrder::getOrderNo, orderNo)
                .eq(customerId != null, WmsOutboundOrder::getCustomerId, customerId)
                .eq(status != null, WmsOutboundOrder::getStatus, status)
                .ge(dateStart != null, WmsOutboundOrder::getOrderDate, dateStart)
                .le(dateEnd != null, WmsOutboundOrder::getOrderDate, dateEnd)
                .eq(WmsOutboundOrder::getInvalidFlag, 0)
                .orderByDesc(WmsOutboundOrder::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "出库单详情（含明细与可用库存）")
    @GetMapping("/detail/{id}")
    public Result<OutboundOrderVO> detail(@PathVariable Long id) {
        return Result.ok(outboundService.detail(id));
    }

    @Operation(summary = "创建出库单")
    @OperationLog("创建出库单")
    @PostMapping("/create")
    public Result<String> create(@Validated @RequestBody OutboundCreateDTO dto) {
        return Result.ok(outboundService.createOrder(dto));
    }

    @Operation(summary = "修改出库单（驳回重提）")
    @OperationLog("修改出库单（驳回重提）")
    @PutMapping("/update")
    public Result<Void> update(@Validated @RequestBody OutboundCreateDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("缺少单据ID");
        }
        outboundService.updateOrder(dto);
        return Result.ok();
    }

    @Operation(summary = "审核出库单（通过后自动生成拣货任务）")
    @OperationLog("审核出库单（通过后自动生成拣货任务）")
    @PutMapping("/audit")
    public Result<Void> audit(@Validated @RequestBody AuditDTO dto) {
        outboundService.audit(dto);
        return Result.ok();
    }

    @Operation(summary = "拣货登记（支持扫码核验）")
    @OperationLog("拣货登记（支持扫码核验）")
    @PutMapping("/pick")
    public Result<Void> pick(@Validated @RequestBody PickDTO dto) {
        outboundService.pick(dto);
        return Result.ok();
    }

    @Operation(summary = "出库复核（扣减库存并写流水）")
    @OperationLog("出库复核（扣减库存并写流水）")
    @PutMapping("/review/{id}")
    public Result<Void> review(@PathVariable Long id) {
        outboundService.review(id);
        return Result.ok();
    }

    @Operation(summary = "导出出库单 Excel")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
                       HttpServletResponse response) throws IOException {
        List<WmsOutboundOrder> orders = orderService.lambdaQuery()
                .ge(dateStart != null, WmsOutboundOrder::getOrderDate, dateStart)
                .le(dateEnd != null, WmsOutboundOrder::getOrderDate, dateEnd)
                .eq(WmsOutboundOrder::getInvalidFlag, 0)
                .orderByDesc(WmsOutboundOrder::getId).list();
        Map<Long, String> customerMap = customerService.list().stream()
                .collect(Collectors.toMap(WmsCustomer::getId, WmsCustomer::getCustomerName));
        Map<Long, String> warehouseMap = warehouseService.list().stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName));
        List<OutboundOrderExcel> rows = orders.stream().map(o -> {
            OutboundOrderExcel e = new OutboundOrderExcel();
            e.setOrderNo(o.getOrderNo());
            e.setCustomerName(customerMap.getOrDefault(o.getCustomerId(), ""));
            e.setWarehouseName(warehouseMap.getOrDefault(o.getWarehouseId(), ""));
            e.setOrderDate(o.getOrderDate());
            e.setTotalAmount(o.getTotalAmount());
            e.setStatusText(switch (o.getStatus()) {
                case 0 -> "待审核";
                case 1 -> "已审核";
                case 2 -> "已驳回";
                case 3 -> "已完成";
                default -> "未知";
            });
            e.setApplicant(o.getApplicant());
            e.setAuditor(o.getAuditor());
            e.setAuditTime(o.getAuditTime());
            e.setRemark(o.getRemark());
            return e;
        }).collect(Collectors.toList());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("出库单.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        EasyExcel.write(response.getOutputStream(), OutboundOrderExcel.class).sheet("出库单").doWrite(rows);
    }
}
