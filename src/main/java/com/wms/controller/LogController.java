package com.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wms.common.Result;
import com.wms.entity.SysOperationLog;
import com.wms.service.SysOperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志管理
 */
@Tag(name = "操作日志管理")
@RestController
@RequestMapping("/log")
@RequiredArgsConstructor
public class LogController {

    private final SysOperationLogService operationLogService;

    @Operation(summary = "操作日志分页查询（时间/人员/内容筛选）")
    @GetMapping("/page")
    public Result<Page<SysOperationLog>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String username,
                                              @RequestParam(required = false) String operation,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        Page<SysOperationLog> page = operationLogService.lambdaQuery()
                .like(StrUtil.isNotBlank(username), SysOperationLog::getUsername, username)
                .like(StrUtil.isNotBlank(operation), SysOperationLog::getOperation, operation)
                .ge(startTime != null, SysOperationLog::getCreateTime, startTime)
                .le(endTime != null, SysOperationLog::getCreateTime, endTime)
                .orderByDesc(SysOperationLog::getId)
                .page(new Page<>(pageNum, pageSize));
        return Result.ok(page);
    }
}
