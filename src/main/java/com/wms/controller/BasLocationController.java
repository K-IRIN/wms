package com.wms.controller;

import com.wms.annotation.OperationLog;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsArea;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsWarehouse;
import com.wms.service.WmsAreaService;
import com.wms.service.WmsLocationService;
import com.wms.service.WmsWarehouseService;
import com.wms.vo.LocationTreeVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库区库位管理：仓库→库区→库位 三级树形结构维护
 */
@Tag(name = "库区库位管理")
@RestController
@RequestMapping("/base/location")
@RequiredArgsConstructor
public class BasLocationController {

    private final WmsWarehouseService warehouseService;
    private final WmsAreaService areaService;
    private final WmsLocationService locationService;

    @Operation(summary = "库区库位树（仓库→库区→库位）")
    @GetMapping("/tree")
    public Result<List<LocationTreeVO>> tree() {
        List<WmsWarehouse> warehouses = warehouseService.lambdaQuery().orderByAsc(WmsWarehouse::getId).list();
        List<WmsArea> areas = areaService.lambdaQuery().orderByAsc(WmsArea::getId).list();
        List<WmsLocation> locations = locationService.lambdaQuery().orderByAsc(WmsLocation::getId).list();

        Map<Long, List<WmsArea>> areaMap = areas.stream().collect(Collectors.groupingBy(WmsArea::getWarehouseId));
        Map<Long, List<WmsLocation>> locMap = locations.stream().collect(Collectors.groupingBy(WmsLocation::getAreaId));

        List<LocationTreeVO> tree = warehouses.stream().map(w -> {
            LocationTreeVO wNode = new LocationTreeVO();
            wNode.setId(w.getId());
            wNode.setNodeType("warehouse");
            wNode.setLabel(w.getWarehouseName());
            wNode.setCode(w.getWarehouseCode());
            wNode.setStatus(w.getStatus());
            for (WmsArea a : areaMap.getOrDefault(w.getId(), List.of())) {
                LocationTreeVO aNode = new LocationTreeVO();
                aNode.setId(a.getId());
                aNode.setNodeType("area");
                aNode.setLabel(a.getAreaName());
                aNode.setCode(a.getAreaCode());
                aNode.setStatus(a.getStatus());
                aNode.setWarehouseId(a.getWarehouseId());
                for (WmsLocation l : locMap.getOrDefault(a.getId(), List.of())) {
                    LocationTreeVO lNode = new LocationTreeVO();
                    lNode.setId(l.getId());
                    lNode.setNodeType("location");
                    lNode.setLabel(l.getLocationCode());
                    lNode.setCode(l.getLocationCode());
                    lNode.setStatus(l.getStatus());
                    lNode.setAreaId(l.getAreaId());
                    lNode.setCapacity(l.getCapacity());
                    lNode.setGoodsType(l.getGoodsType());
                    lNode.setRemark(l.getRemark());
                    aNode.getChildren().add(lNode);
                }
                wNode.getChildren().add(aNode);
            }
            return wNode;
        }).collect(Collectors.toList());
        return Result.ok(tree);
    }

    @Operation(summary = "库位全量列表（AGV 仓库地图用，含 pos_x/pos_y 坐标）")
    @GetMapping("/all")
    public Result<List<WmsLocation>> all() {
        return Result.ok(locationService.lambdaQuery().orderByAsc(WmsLocation::getId).list());
    }

    // ==================== 库区 ====================

    @Operation(summary = "新增库区")
    @OperationLog("新增库区")
    @PostMapping("/area/add")
    public Result<Void> addArea(@RequestBody WmsArea area) {
        if (area.getWarehouseId() == null) {
            throw new BizException("请选择所属仓库");
        }
        long count = areaService.lambdaQuery()
                .eq(WmsArea::getWarehouseId, area.getWarehouseId())
                .eq(WmsArea::getAreaCode, area.getAreaCode())
                .count();
        if (count > 0) {
            throw new BizException("该仓库下库区编号已存在：" + area.getAreaCode());
        }
        area.setId(null);
        if (area.getStatus() == null) {
            area.setStatus(1);
        }
        areaService.save(area);
        return Result.ok();
    }

    @Operation(summary = "编辑库区")
    @OperationLog("编辑库区")
    @PutMapping("/area/update")
    public Result<Void> updateArea(@RequestBody WmsArea area) {
        if (area.getId() == null) {
            throw new BizException("缺少库区ID");
        }
        areaService.updateById(area);
        return Result.ok();
    }

    @Operation(summary = "删除库区")
    @OperationLog("删除库区")
    @DeleteMapping("/area/{id}")
    public Result<Void> deleteArea(@PathVariable Long id) {
        long count = locationService.lambdaQuery().eq(WmsLocation::getAreaId, id).count();
        if (count > 0) {
            throw new BizException("该库区下还有 " + count + " 个库位，请先删除库位");
        }
        areaService.removeById(id);
        return Result.ok();
    }

    // ==================== 库位 ====================

    @Operation(summary = "新增库位")
    @OperationLog("新增库位")
    @PostMapping("/add")
    public Result<Void> addLocation(@RequestBody WmsLocation location) {
        if (location.getAreaId() == null) {
            throw new BizException("请选择所属库区");
        }
        long count = locationService.lambdaQuery()
                .eq(WmsLocation::getAreaId, location.getAreaId())
                .eq(WmsLocation::getLocationCode, location.getLocationCode())
                .count();
        if (count > 0) {
            throw new BizException("该库区下库位编号已存在：" + location.getLocationCode());
        }
        location.setId(null);
        if (location.getStatus() == null) {
            location.setStatus(0);
        }
        parsePosFromCode(location);
        locationService.save(location);
        return Result.ok();
    }

    @Operation(summary = "编辑库位")
    @OperationLog("编辑库位")
    @PutMapping("/update")
    public Result<Void> updateLocation(@RequestBody WmsLocation location) {
        if (location.getId() == null) {
            throw new BizException("缺少库位ID");
        }
        parsePosFromCode(location);
        locationService.updateById(location);
        return Result.ok();
    }

    /** 按库位编号自动解析地图坐标，如 A02-01-03 → pos_x=3, pos_y=1（v2.0 AGV 路径模拟用） */
    private void parsePosFromCode(WmsLocation location) {
        if (location.getLocationCode() == null) {
            return;
        }
        try {
            String[] parts = location.getLocationCode().split("-");
            if (parts.length >= 3) {
                location.setPosY(Integer.parseInt(parts[1]));
                location.setPosX(Integer.parseInt(parts[2]));
            }
        } catch (NumberFormatException ignored) {
            // 编号不符合规则时坐标留空，AGV 调度会将其视为月台位置
        }
    }

    @Operation(summary = "删除库位")
    @OperationLog("删除库位")
    @DeleteMapping("/{id}")
    public Result<Void> deleteLocation(@PathVariable Long id) {
        locationService.removeById(id);
        return Result.ok();
    }
}
