package com.wms.controller;

import com.wms.annotation.OperationLog;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.excel.MaterialExcel;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.entity.WmsMaterialCategory;
import com.wms.service.WmsInventoryService;
import com.wms.service.WmsLocationService;
import com.wms.service.WmsMaterialCategoryService;
import com.wms.service.WmsMaterialService;
import com.wms.vo.CategoryTreeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 物料管理：分类树、物料 CRUD、Excel 导入导出
 */
@Tag(name = "物料管理")
@RestController
@RequestMapping("/base/material")
@RequiredArgsConstructor
public class BasMaterialController {

    private final WmsMaterialService materialService;
    private final WmsMaterialCategoryService categoryService;
    private final WmsInventoryService inventoryService;
    private final WmsLocationService locationService;

    // ==================== 分类树 ====================

    @Operation(summary = "物料分类树")
    @GetMapping("/category/tree")
    public Result<List<CategoryTreeVO>> categoryTree() {
        List<WmsMaterialCategory> all = categoryService.lambdaQuery()
                .orderByAsc(WmsMaterialCategory::getSort)
                .orderByAsc(WmsMaterialCategory::getId)
                .list();
        List<CategoryTreeVO> nodes = all.stream().map(c -> {
            CategoryTreeVO vo = new CategoryTreeVO();
            BeanUtil.copyProperties(c, vo);
            return vo;
        }).collect(Collectors.toList());
        Map<Long, CategoryTreeVO> byId = nodes.stream().collect(Collectors.toMap(CategoryTreeVO::getId, n -> n));
        List<CategoryTreeVO> roots = new ArrayList<>();
        for (CategoryTreeVO node : nodes) {
            if (node.getParentId() != null && byId.containsKey(node.getParentId())) {
                byId.get(node.getParentId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        return Result.ok(roots);
    }

    @Operation(summary = "新增分类")
    @OperationLog("新增分类")
    @PostMapping("/category/add")
    public Result<Void> addCategory(@RequestBody WmsMaterialCategory category) {
        category.setId(null);
        if (category.getParentId() == null) {
            category.setParentId(0L);
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        categoryService.save(category);
        return Result.ok();
    }

    @Operation(summary = "编辑分类")
    @OperationLog("编辑分类")
    @PutMapping("/category/update")
    public Result<Void> updateCategory(@RequestBody WmsMaterialCategory category) {
        if (category.getId() == null) {
            throw new BizException("缺少分类ID");
        }
        categoryService.updateById(category);
        return Result.ok();
    }

    @Operation(summary = "删除分类")
    @OperationLog("删除分类")
    @DeleteMapping("/category/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        long childCount = categoryService.lambdaQuery().eq(WmsMaterialCategory::getParentId, id).count();
        if (childCount > 0) {
            throw new BizException("该分类下还有子分类，无法删除");
        }
        long materialCount = materialService.lambdaQuery().eq(WmsMaterial::getCategoryId, id).count();
        if (materialCount > 0) {
            throw new BizException("该分类下还有 " + materialCount + " 个物料，无法删除");
        }
        categoryService.removeById(id);
        return Result.ok();
    }

    // ==================== 物料 ====================

    @Operation(summary = "启用物料下拉列表")
    @GetMapping("/all")
    public Result<List<WmsMaterial>> all() {
        return Result.ok(materialService.lambdaQuery()
                .eq(WmsMaterial::getStatus, 1)
                .orderByAsc(WmsMaterial::getId)
                .list());
    }

    @Operation(summary = "物料分页查询（含库存总量与存放库位）")
    @GetMapping("/page")
    public Result<Page<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long categoryId,
                                                  @RequestParam(required = false) Integer status) {
        // 选中分类时，连同其所有子孙分类一起过滤
        Set<Long> categoryIds = null;
        if (categoryId != null) {
            categoryIds = collectSubtreeIds(categoryId);
        }
        Set<Long> finalIds = categoryIds;
        Page<WmsMaterial> page = materialService.lambdaQuery()
                .and(StrUtil.isNotBlank(keyword), q -> q.like(WmsMaterial::getMaterialName, keyword)
                        .or().like(WmsMaterial::getMaterialCode, keyword))
                .in(finalIds != null, WmsMaterial::getCategoryId, finalIds)
                .eq(status != null, WmsMaterial::getStatus, status)
                .orderByAsc(WmsMaterial::getId)
                .page(new Page<>(pageNum, pageSize));

        // 聚合当前页物料的库存：总量 + 存放库位分布
        List<Long> materialIds = page.getRecords().stream().map(WmsMaterial::getId).collect(Collectors.toList());
        Map<Long, List<WmsInventory>> invByMaterial = materialIds.isEmpty() ? Map.of()
                : inventoryService.lambdaQuery().in(WmsInventory::getMaterialId, materialIds).list().stream()
                        .collect(Collectors.groupingBy(WmsInventory::getMaterialId));
        Map<Long, String> locationMap = locationService.list().stream()
                .collect(Collectors.toMap(WmsLocation::getId, WmsLocation::getLocationCode));

        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(m -> {
            Map<String, Object> row = new java.util.HashMap<>();
            BeanUtil.copyProperties(m, row);
            List<WmsInventory> invs = invByMaterial.getOrDefault(m.getId(), List.of());
            int totalQty = invs.stream().mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
            int frozenQty = invs.stream().mapToInt(i -> i.getFrozenQty() == null ? 0 : i.getFrozenQty()).sum();
            row.put("totalQty", totalQty);
            row.put("frozenQty", frozenQty);
            // 存放库位，如 "A02-01-01(50) / A02-02-02(30)"；无库存显示 "-"
            String locations = invs.stream()
                    .filter(i -> i.getQuantity() != null && i.getQuantity() > 0)
                    .map(i -> locationMap.getOrDefault(i.getLocationId(), "?") + "(" + i.getQuantity() + ")")
                    .collect(Collectors.joining(" / "));
            row.put("locations", locations.isEmpty() ? "-" : locations);
            return row;
        }).collect(Collectors.toList()));
        return Result.ok(result);
    }

    private Set<Long> collectSubtreeIds(Long rootId) {
        List<WmsMaterialCategory> all = categoryService.list();
        Map<Long, List<WmsMaterialCategory>> byParent = all.stream()
                .collect(Collectors.groupingBy(WmsMaterialCategory::getParentId));
        Set<Long> ids = new java.util.HashSet<>();
        java.util.Deque<Long> stack = new java.util.ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            if (ids.add(cur)) {
                for (WmsMaterialCategory child : byParent.getOrDefault(cur, List.of())) {
                    stack.push(child.getId());
                }
            }
        }
        return ids;
    }

    @Operation(summary = "新增物料")
    @OperationLog("新增物料")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody WmsMaterial material) {
        checkCodeUnique(material.getMaterialCode(), null);
        material.setId(null);
        if (material.getStatus() == null) {
            material.setStatus(1);
        }
        materialService.save(material);
        return Result.ok();
    }

    @Operation(summary = "编辑物料")
    @OperationLog("编辑物料")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody WmsMaterial material) {
        if (material.getId() == null) {
            throw new BizException("缺少物料ID");
        }
        checkCodeUnique(material.getMaterialCode(), material.getId());
        materialService.updateById(material);
        return Result.ok();
    }

    @Operation(summary = "删除物料")
    @OperationLog("删除物料")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        materialService.removeById(id);
        return Result.ok();
    }

    @Operation(summary = "启用/停用物料")
    @OperationLog("启用/停用物料")
    @PutMapping("/status/{id}/{status}")
    public Result<Void> status(@PathVariable Long id, @PathVariable Integer status) {
        WmsMaterial m = new WmsMaterial();
        m.setId(id);
        m.setStatus(status);
        materialService.updateById(m);
        return Result.ok();
    }

    // ==================== Excel 导入导出 ====================

    @Operation(summary = "导出物料 Excel")
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        List<WmsMaterial> list = materialService.lambdaQuery().orderByAsc(WmsMaterial::getId).list();
        List<MaterialExcel> rows = list.stream()
                .map(m -> BeanUtil.copyProperties(m, MaterialExcel.class))
                .collect(Collectors.toList());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("物料数据.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        EasyExcel.write(response.getOutputStream(), MaterialExcel.class).sheet("物料").doWrite(rows);
    }

    @Operation(summary = "导入物料 Excel")
    @OperationLog("导入物料 Excel")
    @PostMapping("/import")
    public Result<String> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的 Excel 文件");
        }
        List<MaterialExcel> rows = EasyExcel.read(file.getInputStream(), MaterialExcel.class, null)
                .sheet().doReadSync();
        if (rows.isEmpty()) {
            throw new BizException("Excel 中没有数据");
        }
        Set<String> existCodes = materialService.list().stream()
                .map(WmsMaterial::getMaterialCode).collect(Collectors.toSet());
        List<WmsMaterial> toSave = new ArrayList<>();
        int skip = 0;
        for (MaterialExcel row : rows) {
            if (StrUtil.isBlank(row.getMaterialCode()) || StrUtil.isBlank(row.getMaterialName())
                    || existCodes.contains(row.getMaterialCode())) {
                skip++;
                continue;
            }
            WmsMaterial m = BeanUtil.copyProperties(row, WmsMaterial.class);
            m.setStatus(1);
            toSave.add(m);
            existCodes.add(m.getMaterialCode());
        }
        if (!toSave.isEmpty()) {
            materialService.saveBatch(toSave);
        }
        return Result.ok("导入完成：成功 " + toSave.size() + " 条，跳过 " + skip + " 条（编号为空/重复）");
    }

    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            throw new BizException("物料编号不能为空");
        }
        long count = materialService.lambdaQuery()
                .eq(WmsMaterial::getMaterialCode, code)
                .ne(excludeId != null, WmsMaterial::getId, excludeId)
                .count();
        if (count > 0) {
            throw new BizException("物料编号已存在：" + code);
        }
    }
}
