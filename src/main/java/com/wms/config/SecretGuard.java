package com.wms.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动期密钥校验：数据库口令与 JWT 密钥只从环境变量读取，缺失或过弱直接拒绝启动。
 * 这样 application.yml 里不再出现任何明文口令/密钥（P0-6）。
 */
@Component
@RequiredArgsConstructor
public class SecretGuard implements InitializingBean {

    /** HS256 要求密钥至少 256 bit，即 32 个字节 */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        String dbPassword = environment.getProperty("spring.datasource.password");
        if (!StringUtils.hasText(dbPassword)) {
            throw new IllegalStateException(
                    "启动失败：数据库口令未配置。请设置环境变量 DB_PASSWORD（本地启动见 tools/run-backend.ps1 读取的 .tmp/secrets.ps1）");
        }

        String jwtSecret = environment.getProperty("jwt.secret");
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException(
                    "启动失败：JWT 密钥未配置。请设置环境变量 JWT_SECRET（本地启动见 tools/run-backend.ps1 读取的 .tmp/secrets.ps1）");
        }
        if (jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "启动失败：JWT_SECRET 过短，HS256 要求至少 " + MIN_JWT_SECRET_BYTES + " 字节");
        }
    }
}
