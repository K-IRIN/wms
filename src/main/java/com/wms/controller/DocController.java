package com.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.annotation.OperationLog;
import com.wms.annotation.RequireRole;
import com.wms.common.Result;
import com.wms.dto.DocCancelDTO;
import com.wms.service.DocService;
import com.wms.vo.DocOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单据中心：四类单据统一查询与作废
 */
@Tag(name = "单据中心")
@RestController
@RequestMapping("/doc")
@RequiredArgsConstructor
public class DocController {

    private final DocService docService;

    @Operation(summary = "单据统一分页查询")
    @GetMapping("/page")
    public Result<Page<DocOrderVO>> page(@RequestParam String type,
                                         @RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize,
                                         @RequestParam(required = false) String orderNo,
                                         @RequestParam(required = false) Integer status) {
        return Result.ok(docService.page(type, pageNum, pageSize, orderNo, status));
    }

    @Operation(summary = "单据作废（必填原因，作废后只读）")
    @OperationLog("作废单据")
    @RequireRole({"ADMIN", "MANAGER"})
    @PutMapping("/cancel")
    public Result<Void> cancel(@Validated @RequestBody DocCancelDTO dto) {
        docService.cancel(dto);
        return Result.ok();
    }
}
