package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.Result;
import com.wms.entity.WmsMoveOrder;
import com.wms.service.InventoryService;
import com.wms.service.WmsMoveOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 库存管理：多维查询、流水追溯、移库、预警、冻结解冻
 */
@Tag(name = "库存管理")
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final WmsMoveOrderService moveOrderService;

    @Operation(summary = "库存汇总（按物料维度）")
    @GetMapping("/summary")
    public Result<List<Map<String, Object>>> summary(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Long warehouseId) {
        return Result.ok(inventoryService.summary(keyword, warehouseId));
    }

    @Operation(summary = "库存明细（按库位维度）")
    @GetMapping("/by-location")
    public Result<List<Map<String, Object>>> byLocation(@RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Long warehouseId) {
        return Result.ok(inventoryService.byLocation(keyword, warehouseId));
    }

    @Operation(summary = "库存变动流水")
    @GetMapping("/logs")
    public Result<List<Map<String, Object>>> logs(@RequestParam(required = false) Long materialId,
                                                  @RequestParam(required = false) String changeType,
                                                  @RequestParam(required = false) String orderNo) {
        return Result.ok(inventoryService.logs(materialId, changeType, orderNo));
    }

    @Operation(summary = "移库（仓内/跨仓）")
    @OperationLog("移库（仓内/跨仓）")
    @PostMapping("/move")
    public Result<String> move(@RequestBody Map<String, Object> body) {
        Long materialId = Long.valueOf(body.get("materialId").toString());
        Long fromLocationId = Long.valueOf(body.get("fromLocationId").toString());
        Long toLocationId = Long.valueOf(body.get("toLocationId").toString());
        Integer quantity = Integer.valueOf(body.get("quantity").toString());
        String remark = body.get("remark") == null ? null : body.get("remark").toString();
        return Result.ok(inventoryService.move(materialId, fromLocationId, toLocationId, quantity, remark));
    }

    @Operation(summary = "移库单分页")
    @GetMapping("/move/page")
    public Result<Page<WmsMoveOrder>> movePage(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String orderNo) {
        return Result.ok(moveOrderService.lambdaQuery()
                .like(orderNo != null && !orderNo.isBlank(), WmsMoveOrder::getOrderNo, orderNo)
                .orderByDesc(WmsMoveOrder::getId)
                .page(new Page<>(pageNum, pageSize)));
    }

    @Operation(summary = "预警中心（库存上下限 + 临期）")
    @GetMapping("/warnings")
    public Result<Map<String, Object>> warnings() {
        return Result.ok(inventoryService.warnings());
    }

    @Operation(summary = "冻结库存")
    @OperationLog("冻结库存")
    @PutMapping("/freeze")
    public Result<Void> freeze(@RequestBody Map<String, Object> body) {
        Long inventoryId = Long.valueOf(body.get("inventoryId").toString());
        Integer qty = Integer.valueOf(body.get("qty").toString());
        inventoryService.freeze(inventoryId, qty, false);
        return Result.ok();
    }

    @Operation(summary = "解冻库存")
    @OperationLog("解冻库存")
    @PutMapping("/unfreeze")
    public Result<Void> unfreeze(@RequestBody Map<String, Object> body) {
        Long inventoryId = Long.valueOf(body.get("inventoryId").toString());
        Integer qty = Integer.valueOf(body.get("qty").toString());
        inventoryService.freeze(inventoryId, qty, true);
        return Result.ok();
    }
}
