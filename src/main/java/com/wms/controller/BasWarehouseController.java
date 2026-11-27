package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.wms.annotation.RequireRole;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsArea;
import com.wms.entity.WmsWarehouse;
import com.wms.service.WmsAreaService;
import com.wms.service.WmsWarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库管理：分页查询、新增、编辑、删除、启停
 */
@Tag(name = "仓库管理")
@RestController
@RequestMapping("/base/warehouse")
@RequiredArgsConstructor
public class BasWarehouseController {

    private final WmsWarehouseService warehouseService;
    private final WmsAreaService areaService;

    @Operation(summary = "仓库分页查询")
    @GetMapping("/page")
    public Result<Page<WmsWarehouse>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status) {
        Page<WmsWarehouse> page = warehouseService.lambdaQuery()
                .and(StrUtil.isNotBlank(keyword), q -> q.like(WmsWarehouse::getWarehouseName, keyword)
                        .or().like(WmsWarehouse::getWarehouseCode, keyword))
                .eq(status != null, WmsWarehouse::getStatus, status)
                .orderByAsc(WmsWarehouse::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "启用仓库下拉列表")
    @GetMapping("/all")
    public Result<List<WmsWarehouse>> all() {
        return Result.ok(warehouseService.lambdaQuery()
                .eq(WmsWarehouse::getStatus, 1)
                .orderByAsc(WmsWarehouse::getId)
                .list());
    }

    @Operation(summary = "新增仓库")
    @OperationLog("新增仓库")
    @RequireRole({"ADMIN", "MANAGER"})
    @PostMapping("/add")
    public Result<Void> add(@RequestBody WmsWarehouse warehouse) {
        checkCodeUnique(warehouse.getWarehouseCode(), null);
        warehouse.setId(null);
        if (warehouse.getStatus() == null) {
            warehouse.setStatus(1);
        }
        warehouseService.save(warehouse);
        return Result.ok();
    }

    @Operation(summary = "编辑仓库")
    @OperationLog("编辑仓库")
    @RequireRole({"ADMIN", "MANAGER"})
    @PutMapping("/update")
    public Result<Void> update(@RequestBody WmsWarehouse warehouse) {
        if (warehouse.getId() == null) {
            throw new BizException("缺少仓库ID");
        }
        checkCodeUnique(warehouse.getWarehouseCode(), warehouse.getId());
        warehouseService.updateById(warehouse);
        return Result.ok();
    }

    @Operation(summary = "删除仓库")
    @OperationLog("删除仓库")
    @RequireRole({"ADMIN", "MANAGER"})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        long areaCount = areaService.lambdaQuery().eq(WmsArea::getWarehouseId, id).count();
        if (areaCount > 0) {
            throw new BizException("该仓库下还有 " + areaCount + " 个库区，请先删除库区");
        }
        warehouseService.removeById(id);
        return Result.ok();
    }

    @Operation(summary = "启用/停用仓库")
    @OperationLog("启用/停用仓库")
    @RequireRole({"ADMIN", "MANAGER"})
    @PutMapping("/status/{id}/{status}")
    public Result<Void> status(@PathVariable Long id, @PathVariable Integer status) {
        WmsWarehouse w = new WmsWarehouse();
        w.setId(id);
        w.setStatus(status);
        warehouseService.updateById(w);
        return Result.ok();
    }

    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            throw new BizException("仓库编号不能为空");
        }
        long count = warehouseService.lambdaQuery()
                .eq(WmsWarehouse::getWarehouseCode, code)
                .ne(excludeId != null, WmsWarehouse::getId, excludeId)
                .count();
        if (count > 0) {
            throw new BizException("仓库编号已存在：" + code);
        }
    }
}
