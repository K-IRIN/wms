package com.wms.controller;

import com.wms.annotation.OperationLog;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.AuditDTO;
import com.wms.dto.InboundCreateDTO;
import com.wms.dto.QualityDTO;
import com.wms.dto.ShelfDTO;
import com.wms.dto.excel.InboundOrderExcel;
import com.wms.entity.WmsInboundOrder;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsSupplier;
import com.wms.entity.WmsWarehouse;
import com.wms.service.InboundService;
import com.wms.service.WmsInboundOrderService;
import com.wms.service.WmsSupplierService;
import com.wms.service.WmsWarehouseService;
import com.wms.vo.InboundOrderVO;
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
 * 采购入库管理
 */
@Tag(name = "采购入库管理")
@RestController
@RequestMapping("/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;
    private final WmsInboundOrderService orderService;
    private final WmsSupplierService supplierService;
    private final WmsWarehouseService warehouseService;

    @Operation(summary = "入库单分页查询")
    @GetMapping("/page")
    public Result<Page<WmsInboundOrder>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String orderNo,
                                              @RequestParam(required = false) Long supplierId,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd) {
        Page<WmsInboundOrder> page = orderService.lambdaQuery()
                .like(StrUtil.isNotBlank(orderNo), WmsInboundOrder::getOrderNo, orderNo)
                .eq(supplierId != null, WmsInboundOrder::getSupplierId, supplierId)
                .eq(status != null, WmsInboundOrder::getStatus, status)
                .ge(dateStart != null, WmsInboundOrder::getOrderDate, dateStart)
                .le(dateEnd != null, WmsInboundOrder::getOrderDate, dateEnd)
                .eq(WmsInboundOrder::getInvalidFlag, 0)
                .orderByDesc(WmsInboundOrder::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "入库单详情（含明细）")
    @GetMapping("/detail/{id}")
    public Result<InboundOrderVO> detail(@PathVariable Long id) {
        return Result.ok(inboundService.detail(id));
    }

    @Operation(summary = "创建入库单")
    @OperationLog("创建入库单")
    @PostMapping("/create")
    public Result<String> create(@Validated @RequestBody InboundCreateDTO dto) {
        return Result.ok(inboundService.createOrder(dto));
    }

    @Operation(summary = "修改入库单（驳回重提）")
    @OperationLog("修改入库单（驳回重提）")
    @PutMapping("/update")
    public Result<Void> update(@Validated @RequestBody InboundCreateDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("缺少单据ID");
        }
        inboundService.updateOrder(dto);
        return Result.ok();
    }

    @Operation(summary = "质检登记")
    @OperationLog("质检登记")
    @PutMapping("/quality")
    public Result<Void> quality(@Validated @RequestBody QualityDTO dto) {
        inboundService.quality(dto);
        return Result.ok();
    }

    @Operation(summary = "扫码带出物料信息")
    @GetMapping("/scan/{materialCode}")
    public Result<WmsMaterial> scan(@PathVariable String materialCode) {
        return Result.ok(inboundService.scanMaterial(materialCode));
    }

    @Operation(summary = "智能推荐空闲库位")
    @GetMapping("/recommend-location")
    public Result<List<WmsLocation>> recommend(@RequestParam Long materialId, @RequestParam Long warehouseId) {
        return Result.ok(inboundService.recommendLocations(materialId, warehouseId));
    }

    @Operation(summary = "仓库全部空闲库位（手动指定）")
    @GetMapping("/free-locations")
    public Result<List<WmsLocation>> freeLocations(@RequestParam Long warehouseId) {
        return Result.ok(inboundService.freeLocations(warehouseId));
    }

    @Operation(summary = "上架登记")
    @OperationLog("上架登记")
    @PutMapping("/shelf")
    public Result<Void> shelf(@Validated @RequestBody ShelfDTO dto) {
        inboundService.shelf(dto);
        return Result.ok();
    }

    @Operation(summary = "取消上架")
    @OperationLog("取消上架")
    @PutMapping("/cancel-shelf/{itemId}")
    public Result<Void> cancelShelf(@PathVariable Long itemId) {
        inboundService.cancelShelf(itemId);
        return Result.ok();
    }

    @Operation(summary = "审核入库单")
    @OperationLog("审核入库单")
    @PutMapping("/audit")
    public Result<Void> audit(@Validated @RequestBody AuditDTO dto) {
        inboundService.audit(dto);
        return Result.ok();
    }

    @Operation(summary = "导出入库单 Excel")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
                       HttpServletResponse response) throws IOException {
        List<WmsInboundOrder> orders = orderService.lambdaQuery()
                .ge(dateStart != null, WmsInboundOrder::getOrderDate, dateStart)
                .le(dateEnd != null, WmsInboundOrder::getOrderDate, dateEnd)
                .eq(WmsInboundOrder::getInvalidFlag, 0)
                .orderByDesc(WmsInboundOrder::getId).list();
        Map<Long, String> supplierMap = supplierService.list().stream()
                .collect(Collectors.toMap(WmsSupplier::getId, WmsSupplier::getSupplierName));
        Map<Long, String> warehouseMap = warehouseService.list().stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName));
        List<InboundOrderExcel> rows = orders.stream().map(o -> {
            InboundOrderExcel e = new InboundOrderExcel();
            e.setOrderNo(o.getOrderNo());
            e.setSupplierName(supplierMap.getOrDefault(o.getSupplierId(), ""));
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
        String fileName = URLEncoder.encode("入库单.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        EasyExcel.write(response.getOutputStream(), InboundOrderExcel.class).sheet("入库单").doWrite(rows);
    }
}
