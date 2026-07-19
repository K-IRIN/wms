package com.wms.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.excel.SupplierExcel;
import com.wms.entity.WmsSupplier;
import com.wms.service.WmsSupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商管理：CRUD + 合作状态管理（终止合作而非删除）+ Excel 导入导出
 */
@Tag(name = "供应商管理")
@RestController
@RequestMapping("/base/supplier")
@RequiredArgsConstructor
public class BasSupplierController {

    private final WmsSupplierService supplierService;

    @Operation(summary = "供应商分页查询")
    @GetMapping("/page")
    public Result<Page<WmsSupplier>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Integer cooperateStatus) {
        Page<WmsSupplier> page = supplierService.lambdaQuery()
                .and(StrUtil.isNotBlank(keyword), q -> q.like(WmsSupplier::getSupplierName, keyword)
                        .or().like(WmsSupplier::getSupplierCode, keyword)
                        .or().like(WmsSupplier::getContact, keyword))
                .eq(cooperateStatus != null, WmsSupplier::getCooperateStatus, cooperateStatus)
                .orderByAsc(WmsSupplier::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "合作中供应商下拉列表")
    @GetMapping("/all")
    public Result<List<WmsSupplier>> all() {
        return Result.ok(supplierService.lambdaQuery()
                .eq(WmsSupplier::getCooperateStatus, 1)
                .orderByAsc(WmsSupplier::getId)
                .list());
    }

    @Operation(summary = "新增供应商")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody WmsSupplier supplier) {
        checkCodeUnique(supplier.getSupplierCode(), null);
        supplier.setId(null);
        if (supplier.getCooperateStatus() == null) {
            supplier.setCooperateStatus(1);
        }
        supplierService.save(supplier);
        return Result.ok();
    }

    @Operation(summary = "编辑供应商")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody WmsSupplier supplier) {
        if (supplier.getId() == null) {
            throw new BizException("缺少供应商ID");
        }
        checkCodeUnique(supplier.getSupplierCode(), supplier.getId());
        supplierService.updateById(supplier);
        return Result.ok();
    }

    @Operation(summary = "恢复/终止合作（禁用而非删除）")
    @PutMapping("/status/{id}/{status}")
    public Result<Void> status(@PathVariable Long id, @PathVariable Integer status) {
        WmsSupplier s = new WmsSupplier();
        s.setId(id);
        s.setCooperateStatus(status);
        supplierService.updateById(s);
        return Result.ok();
    }

    @Operation(summary = "导出供应商 Excel")
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        List<SupplierExcel> rows = supplierService.lambdaQuery().orderByAsc(WmsSupplier::getId).list()
                .stream().map(s -> BeanUtil.copyProperties(s, SupplierExcel.class)).collect(Collectors.toList());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("供应商数据.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        EasyExcel.write(response.getOutputStream(), SupplierExcel.class).sheet("供应商").doWrite(rows);
    }

    @Operation(summary = "导入供应商 Excel")
    @PostMapping("/import")
    public Result<String> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的 Excel 文件");
        }
        List<SupplierExcel> rows = EasyExcel.read(file.getInputStream(), SupplierExcel.class, null)
                .sheet().doReadSync();
        if (rows.isEmpty()) {
            throw new BizException("Excel 中没有数据");
        }
        Set<String> existCodes = supplierService.list().stream()
                .map(WmsSupplier::getSupplierCode).collect(Collectors.toSet());
        List<WmsSupplier> toSave = new ArrayList<>();
        int skip = 0;
        for (SupplierExcel row : rows) {
            if (StrUtil.isBlank(row.getSupplierCode()) || StrUtil.isBlank(row.getSupplierName())
                    || existCodes.contains(row.getSupplierCode())) {
                skip++;
                continue;
            }
            WmsSupplier s = BeanUtil.copyProperties(row, WmsSupplier.class);
            s.setCooperateStatus(1);
            toSave.add(s);
            existCodes.add(s.getSupplierCode());
        }
        if (!toSave.isEmpty()) {
            supplierService.saveBatch(toSave);
        }
        return Result.ok("导入完成：成功 " + toSave.size() + " 条，跳过 " + skip + " 条（编号为空/重复）");
    }

    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            throw new BizException("供应商编号不能为空");
        }
        long count = supplierService.lambdaQuery()
                .eq(WmsSupplier::getSupplierCode, code)
                .ne(excludeId != null, WmsSupplier::getId, excludeId)
                .count();
        if (count > 0) {
            throw new BizException("供应商编号已存在：" + code);
        }
    }
}
