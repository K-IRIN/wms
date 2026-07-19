package com.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.wms.common.BizException;
import com.wms.common.Result;
import com.wms.entity.WmsInventory;
import com.wms.entity.WmsLocation;
import com.wms.entity.WmsMaterial;
import com.wms.service.WmsInventoryService;
import com.wms.service.WmsLocationService;
import com.wms.service.WmsMaterialService;
import com.wms.utils.QrCodeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 二维码管理：物料/库位二维码生成、批量打包、扫码查库位库存
 */
@Tag(name = "二维码管理")
@RestController
@RequestMapping("/qrcode")
@RequiredArgsConstructor
public class QrCodeController {

    private final WmsMaterialService materialService;
    private final WmsLocationService locationService;
    private final WmsInventoryService inventoryService;

    @Operation(summary = "物料二维码（内容为物料编号）")
    @GetMapping("/material/{id}")
    public void materialQr(@PathVariable Long id, HttpServletResponse response) throws IOException {
        WmsMaterial m = materialService.getById(id);
        if (m == null) {
            throw new BizException("物料不存在");
        }
        writePng(response, QrCodeUtils.png(m.getMaterialCode(), 240));
    }

    @Operation(summary = "库位二维码（内容为库位编号）")
    @GetMapping("/location/{id}")
    public void locationQr(@PathVariable Long id, HttpServletResponse response) throws IOException {
        WmsLocation l = locationService.getById(id);
        if (l == null) {
            throw new BizException("库位不存在");
        }
        writePng(response, QrCodeUtils.png(l.getLocationCode(), 240));
    }

    @Operation(summary = "批量物料二维码打包 ZIP")
    @GetMapping("/material/batch")
    public void materialBatch(@RequestParam List<Long> ids, HttpServletResponse response) throws IOException {
        List<WmsMaterial> materials = materialService.listByIds(ids);
        if (materials.isEmpty()) {
            throw new BizException("请选择要生成的物料");
        }
        response.setContentType("application/zip");
        String fileName = URLEncoder.encode("物料二维码.zip", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            for (WmsMaterial m : materials) {
                zip.putNextEntry(new ZipEntry(m.getMaterialCode() + "_" + m.getMaterialName() + ".png"));
                zip.write(QrCodeUtils.png(m.getMaterialCode(), 300));
                zip.closeEntry();
            }
        }
    }

    @Operation(summary = "批量库位二维码打包 ZIP")
    @GetMapping("/location/batch")
    public void locationBatch(@RequestParam List<Long> ids, HttpServletResponse response) throws IOException {
        List<WmsLocation> locations = locationService.listByIds(ids);
        if (locations.isEmpty()) {
            throw new BizException("请选择要生成的库位");
        }
        response.setContentType("application/zip");
        String fileName = URLEncoder.encode("库位二维码.zip", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName);
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            for (WmsLocation l : locations) {
                zip.putNextEntry(new ZipEntry(l.getLocationCode() + ".png"));
                zip.write(QrCodeUtils.png(l.getLocationCode(), 300));
                zip.closeEntry();
            }
        }
    }

    @Operation(summary = "扫码查库位库存（扫库位二维码）")
    @GetMapping("/location-stock/{locationCode}")
    public Result<List<Map<String, Object>>> locationStock(@PathVariable String locationCode) {
        WmsLocation l = locationService.lambdaQuery()
                .eq(WmsLocation::getLocationCode, locationCode).one();
        if (l == null) {
            throw new BizException("未找到库位：" + locationCode);
        }
        List<WmsInventory> invs = inventoryService.lambdaQuery()
                .eq(WmsInventory::getLocationId, l.getId())
                .gt(WmsInventory::getQuantity, 0).list();
        Map<Long, WmsMaterial> materialMap = new HashMap<>();
        materialService.list().forEach(m -> materialMap.put(m.getId(), m));
        List<Map<String, Object>> rows = invs.stream().map(inv -> {
            WmsMaterial m = materialMap.get(inv.getMaterialId());
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("materialCode", m == null ? "?" : m.getMaterialCode());
            row.put("materialName", m == null ? "?" : m.getMaterialName());
            row.put("spec", m == null ? "" : m.getSpec());
            row.put("unit", m == null ? "" : m.getUnit());
            row.put("quantity", inv.getQuantity());
            row.put("frozenQty", inv.getFrozenQty());
            return row;
        }).toList();
        return Result.ok(rows);
    }

    @Operation(summary = "扫码查物料（扫物料二维码）")
    @GetMapping("/material-info/{materialCode}")
    public Result<WmsMaterial> materialInfo(@PathVariable String materialCode) {
        WmsMaterial m = materialService.lambdaQuery()
                .eq(WmsMaterial::getMaterialCode, materialCode).one();
        if (m == null) {
            throw new BizException("未找到物料：" + materialCode);
        }
        return Result.ok(m);
    }

    private void writePng(HttpServletResponse response, byte[] bytes) throws IOException {
        response.setContentType("image/png");
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
}
