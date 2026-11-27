package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.wms.annotation.RequireRole;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.Result;
import com.wms.dto.AuditDTO;
import com.wms.dto.CheckCreateDTO;
import com.wms.dto.CheckEntryDTO;
import com.wms.dto.excel.CheckOrderExcel;
import com.wms.entity.WmsCheckOrder;
import com.wms.entity.WmsWarehouse;
import com.wms.service.CheckService;
import com.wms.service.WmsCheckOrderService;
import com.wms.service.WmsWarehouseService;
import com.wms.vo.CheckOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存盘点管理
 */
@Tag(name = "库存盘点管理")
@RestController
@RequestMapping("/check")
@RequiredArgsConstructor
public class CheckController {

    private final CheckService checkService;
    private final WmsCheckOrderService orderService;
    private final WmsWarehouseService warehouseService;

    @Operation(summary = "盘点单分页查询")
    @GetMapping("/page")
    public Result<Page<WmsCheckOrder>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String orderNo,
                                            @RequestParam(required = false) Long warehouseId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String checkPeriod) {
        Page<WmsCheckOrder> page = orderService.lambdaQuery()
                .like(StrUtil.isNotBlank(orderNo), WmsCheckOrder::getOrderNo, orderNo)
                .eq(warehouseId != null, WmsCheckOrder::getWarehouseId, warehouseId)
                .eq(status != null, WmsCheckOrder::getStatus, status)
                .eq(StrUtil.isNotBlank(checkPeriod), WmsCheckOrder::getCheckPeriod, checkPeriod)
                .orderByDesc(WmsCheckOrder::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "盘点单详情（含明细）")
    @GetMapping("/detail/{id}")
    public Result<CheckOrderVO> detail(@PathVariable Long id) {
        return Result.ok(checkService.detail(id));
    }

    @Operation(summary = "创建盘点单（快照账面库存）")
    @OperationLog("创建盘点单（快照账面库存）")
    @RequireRole({"ADMIN", "MANAGER", "OPERATOR"})
    @PostMapping("/create")
    public Result<String> create(@Validated @RequestBody CheckCreateDTO dto) {
        return Result.ok(checkService.createOrder(dto));
    }

    @Operation(summary = "实盘录入")
    @OperationLog("实盘录入")
    @RequireRole({"ADMIN", "MANAGER", "OPERATOR"})
    @PutMapping("/entry")
    public Result<Void> entry(@Validated @RequestBody CheckEntryDTO dto) {
        checkService.entry(dto);
        return Result.ok();
    }

    @Operation(summary = "提交审核")
    @OperationLog("提交审核")
    @RequireRole({"ADMIN", "MANAGER", "OPERATOR"})
    @PutMapping("/submit/{id}")
    public Result<Void> submit(@PathVariable Long id) {
        checkService.submit(id);
        return Result.ok();
    }

    @Operation(summary = "盘点差异审核")
    @OperationLog("盘点差异审核")
    @RequireRole({"ADMIN", "MANAGER"})
    @PutMapping("/audit")
    public Result<Void> audit(@Validated @RequestBody AuditDTO dto) {
        checkService.audit(dto);
        return Result.ok();
    }

    @Operation(summary = "导出盘点单 Excel")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String checkPeriod,
                       HttpServletResponse response) throws IOException {
        List<WmsCheckOrder> orders = orderService.lambdaQuery()
                .eq(StrUtil.isNotBlank(checkPeriod), WmsCheckOrder::getCheckPeriod, checkPeriod)
                .orderByDesc(WmsCheckOrder::getId).list();
        Map<Long, String> warehouseMap = warehouseService.list().stream()
                .collect(Collectors.toMap(WmsWarehouse::getId, WmsWarehouse::getWarehouseName));
        List<CheckOrderExcel> rows = orders.stream().map(o -> {
            CheckOrderExcel e = new CheckOrderExcel();
            e.setOrderNo(o.getOrderNo());
            e.setWarehouseName(warehouseMap.getOrDefault(o.getWarehouseId(), ""));
            e.setScopeText(switch (o.getScopeType()) {
                case 1 -> "按仓库";
                case 2 -> "按库区";
                case 3 -> "按物料分类";
                default -> "未知";
            });
            e.setCheckPeriod(o.getCheckPeriod());
            e.setStatusText(switch (o.getStatus()) {
                case 0 -> "待盘点";
                case 1 -> "待审核";
                case 3 -> "已完成";
                default -> "未知";
            });
            e.setChecker(o.getChecker());
            e.setAuditor(o.getAuditor());
            e.setAuditTime(o.getAuditTime());
            e.setCreateTime(o.getCreateTime());
            e.setRemark(o.getRemark());
            return e;
        }).collect(Collectors.toList());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("盘点单.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        EasyExcel.write(response.getOutputStream(), CheckOrderExcel.class).sheet("盘点单").doWrite(rows);
    }
}
