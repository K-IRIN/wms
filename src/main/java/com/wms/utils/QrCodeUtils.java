package com.wms.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.wms.common.BizException;

import java.io.ByteArrayOutputStream;

/**
 * ZXing 二维码生成工具
 */
public class QrCodeUtils {

    private QrCodeUtils() {
    }

    /** 生成二维码 PNG 字节 */
    public static byte[] png(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("二维码生成失败：" + e.getMessage());
        }
    }
}
