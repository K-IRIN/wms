package com.wms.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.wms.annotation.OperationLog;
import com.wms.common.Result;
import com.wms.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据统计与可视化分析
 */
@Tag(name = "数据统计与可视化分析")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "顶部统计卡片")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(dashboardService.summary());
    }

    @Operation(summary = "出入库总量趋势（day/week/month）")
    @GetMapping("/inout-trend")
    public Result<Map<String, Object>> inoutTrend(@RequestParam(defaultValue = "day") String period) {
        return Result.ok(dashboardService.inoutTrend(period));
    }

    @Operation(summary = "物料品类占比分析")
    @GetMapping("/category-ratio")
    public Result<Map<String, Object>> categoryRatio() {
        return Result.ok(dashboardService.categoryRatio());
    }

    @Operation(summary = "盘点分析")
    @GetMapping("/check-analysis")
    public Result<Map<String, Object>> checkAnalysis() {
        return Result.ok(dashboardService.checkAnalysis());
    }

    @Operation(summary = "库存周转分析（高频/滞销）")
    @GetMapping("/turnover")
    public Result<Map<String, Object>> turnover(@RequestParam(defaultValue = "30") int slowDays) {
        return Result.ok(dashboardService.turnover(slowDays));
    }

    @Operation(summary = "员工绩效统计")
    @GetMapping("/staff-performance")
    public Result<List<Map<String, Object>>> staffPerformance() {
        return Result.ok(dashboardService.staffPerformance());
    }

    @Operation(summary = "预警统计报表")
    @GetMapping("/warning-report")
    public Result<Map<String, Object>> warningReport() {
        return Result.ok(dashboardService.warningReport());
    }

    @Operation(summary = "统计报表导出（Excel 多页签）")
    @OperationLog("导出统计报表")
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("WMS统计报表", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        Map<String, Object> summary = dashboardService.summary();
        Map<String, Object> trend = dashboardService.inoutTrend("day");
        Map<String, Object> ratio = dashboardService.categoryRatio();
        Map<String, Object> check = dashboardService.checkAnalysis();
        Map<String, Object> turnover = dashboardService.turnover(30);
        List<Map<String, Object>> staff = dashboardService.staffPerformance();
        Map<String, Object> warning = dashboardService.warningReport();

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            // 页签1：统计概览
            WriteSheet s0 = EasyExcel.writerSheet(0, "统计概览").head(List.of(
                    List.of("指标"), List.of("数值"))).build();
            writer.write(List.of(
                    List.of("物料总数", summary.get("materialCount")),
                    List.of("库存总量", summary.get("totalStock")),
                    List.of("今日入库单数", summary.get("todayInbound")),
                    List.of("今日出库单数", summary.get("todayOutbound")),
                    List.of("待审核单据", summary.get("pendingAudit")),
                    List.of("预警总数", summary.get("warningCount")),
                    List.of("盘点总次数", check.get("totalOrders")),
                    List.of("盘点准确率(%)", check.get("accuracy")),
                    List.of("盘盈次数", check.get("profitCount")),
                    List.of("盘亏次数", check.get("lossCount")),
                    List.of("盈亏总量", check.get("diffTotal")),
                    List.of("短缺预警数", warning.get("shortageCount")),
                    List.of("积压预警数", warning.get("overstockCount")),
                    List.of("临期预警数", warning.get("expiryCount"))), s0);

            // 页签2：近30天出入库趋势
            WriteSheet s1 = EasyExcel.writerSheet(1, "出入库趋势(近30天)").head(List.of(
                    List.of("日期"), List.of("入库量"), List.of("出库量"))).build();
            List<?> labels = (List<?>) trend.get("labels");
            List<?> ins = (List<?>) trend.get("inbound");
            List<?> outs = (List<?>) trend.get("outbound");
            List<List<Object>> trendRows = new java.util.ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                trendRows.add(List.of(labels.get(i), ins.get(i), outs.get(i)));
            }
            writer.write(trendRows, s1);

            // 页签3：品类库存占比
            WriteSheet s2 = EasyExcel.writerSheet(2, "品类库存占比").head(List.of(
                    List.of("品类"), List.of("库存数量"))).build();
            List<List<Object>> ratioRows = ((List<Map<String, Object>>) ratio.get("stockRatio")).stream()
                    .map(r -> List.<Object>of(r.get("name"), r.get("value")))
                    .collect(java.util.stream.Collectors.toList());
            writer.write(ratioRows, s2);

            // 页签4：周转分析（高频 + 滞销）
            WriteSheet s3 = EasyExcel.writerSheet(3, "周转分析").head(List.of(
                    List.of("类型"), List.of("物料编码"), List.of("物料名称"),
                    List.of("出库次数"), List.of("出库总量"), List.of("当前库存"), List.of("最近出库"))).build();
            List<List<Object>> turnRows = new java.util.ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) turnover.get("highFreq")) {
                turnRows.add(List.of("高频", r.get("materialCode"), r.get("materialName"),
                        r.get("outCount"), r.get("outQty"), r.get("currentStock"), "-"));
            }
            for (Map<String, Object> r : (List<Map<String, Object>>) turnover.get("slowMoving")) {
                turnRows.add(List.of("滞销", r.get("materialCode"), r.get("materialName"),
                        "-", "-", r.get("currentStock"), r.get("lastOutTime")));
            }
            writer.write(turnRows, s3);

            // 页签5：员工绩效
            WriteSheet s4 = EasyExcel.writerSheet(4, "员工绩效").head(List.of(
                    List.of("操作人"), List.of("入库量"), List.of("出库量"),
                    List.of("盘点次数"), List.of("制单数"))).build();
            List<List<Object>> staffRows = staff.stream()
                    .map(r -> List.<Object>of(r.get("operator"), r.get("inboundQty"),
                            r.get("outboundQty"), r.get("checkCount"), r.get("docCount")))
                    .collect(java.util.stream.Collectors.toList());
            writer.write(staffRows, s4);
        }
    }
}
