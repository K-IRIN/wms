package com.wms.controller;

import com.wms.annotation.OperationLog;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.dto.excel.CustomerExcel;
import com.wms.entity.WmsCustomer;
import com.wms.service.WmsCustomerService;
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
 * 客户管理：CRUD + 状态管理（停用而非删除）+ Excel 导入导出
 */
@Tag(name = "客户管理")
@RestController
@RequestMapping("/base/customer")
@RequiredArgsConstructor
public class BasCustomerController {

    private final WmsCustomerService customerService;

    @Operation(summary = "客户分页查询")
    @GetMapping("/page")
    public Result<Page<WmsCustomer>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String level,
                                          @RequestParam(required = false) Integer status) {
        Page<WmsCustomer> page = customerService.lambdaQuery()
                .and(StrUtil.isNotBlank(keyword), q -> q.like(WmsCustomer::getCustomerName, keyword)
                        .or().like(WmsCustomer::getCustomerCode, keyword)
                        .or().like(WmsCustomer::getContact, keyword))
                .eq(StrUtil.isNotBlank(level), WmsCustomer::getLevel, level)
                .eq(status != null, WmsCustomer::getStatus, status)
                .orderByAsc(WmsCustomer::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }

    @Operation(summary = "启用客户下拉列表")
    @GetMapping("/all")
    public Result<List<WmsCustomer>> all() {
        return Result.ok(customerService.lambdaQuery()
                .eq(WmsCustomer::getStatus, 1)
                .orderByAsc(WmsCustomer::getId)
                .list());
    }

    @Operation(summary = "新增客户")
    @OperationLog("新增客户")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody WmsCustomer customer) {
        checkCodeUnique(customer.getCustomerCode(), null);
        customer.setId(null);
        if (customer.getStatus() == null) {
            customer.setStatus(1);
        }
        customerService.save(customer);
        return Result.ok();
    }

    @Operation(summary = "编辑客户")
    @OperationLog("编辑客户")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody WmsCustomer customer) {
        if (customer.getId() == null) {
            throw new BizException("缺少客户ID");
        }
        checkCodeUnique(customer.getCustomerCode(), customer.getId());
        customerService.updateById(customer);
        return Result.ok();
    }

    @Operation(summary = "启用/停用客户（禁用而非删除）")
    @OperationLog("启用/停用客户（禁用而非删除）")
    @PutMapping("/status/{id}/{status}")
    public Result<Void> status(@PathVariable Long id, @PathVariable Integer status) {
        WmsCustomer c = new WmsCustomer();
        c.setId(id);
        c.setStatus(status);
        customerService.updateById(c);
        return Result.ok();
    }

    @Operation(summary = "导出客户 Excel")
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        List<CustomerExcel> rows = customerService.lambdaQuery().orderByAsc(WmsCustomer::getId).list()
                .stream().map(c -> BeanUtil.copyProperties(c, CustomerExcel.class)).collect(Collectors.toList());
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("客户数据.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        EasyExcel.write(response.getOutputStream(), CustomerExcel.class).sheet("客户").doWrite(rows);
    }

    @Operation(summary = "导入客户 Excel")
    @OperationLog("导入客户 Excel")
    @PostMapping("/import")
    public Result<String> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的 Excel 文件");
        }
        List<CustomerExcel> rows = EasyExcel.read(file.getInputStream(), CustomerExcel.class, null)
                .sheet().doReadSync();
        if (rows.isEmpty()) {
            throw new BizException("Excel 中没有数据");
        }
        Set<String> existCodes = customerService.list().stream()
                .map(WmsCustomer::getCustomerCode).collect(Collectors.toSet());
        List<WmsCustomer> toSave = new ArrayList<>();
        int skip = 0;
        for (CustomerExcel row : rows) {
            if (StrUtil.isBlank(row.getCustomerCode()) || StrUtil.isBlank(row.getCustomerName())
                    || existCodes.contains(row.getCustomerCode())) {
                skip++;
                continue;
            }
            WmsCustomer c = BeanUtil.copyProperties(row, WmsCustomer.class);
            c.setStatus(1);
            toSave.add(c);
            existCodes.add(c.getCustomerCode());
        }
        if (!toSave.isEmpty()) {
            customerService.saveBatch(toSave);
        }
        return Result.ok("导入完成：成功 " + toSave.size() + " 条，跳过 " + skip + " 条（编号为空/重复）");
    }

    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            throw new BizException("客户编号不能为空");
        }
        long count = customerService.lambdaQuery()
                .eq(WmsCustomer::getCustomerCode, code)
                .ne(excludeId != null, WmsCustomer::getId, excludeId)
                .count();
        if (count > 0) {
            throw new BizException("客户编号已存在：" + code);
        }
    }
}
