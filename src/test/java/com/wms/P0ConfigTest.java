package com.wms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7（纯配置用例，不需要数据库）：application.yml 不得出现明文数据库口令与 JWT 密钥。
 *
 * <p>对应报告 P0-6。口令/密钥只能通过环境变量 DB_PASSWORD / JWT_SECRET 注入。
 */
class P0ConfigTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    @Test
    @DisplayName("T7 application.yml 无明文口令/密钥")
    void t7_noPlaintextSecret() throws IOException {
        String yml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);

        assertFalse(yml.contains("password: 123456"),
                "application.yml 仍含明文数据库口令：password: 123456");
        assertFalse(yml.contains("wms-graduation-project-secret-key"),
                "application.yml 仍含明文 JWT 密钥：wms-graduation-project-secret-key");
        assertTrue(yml.contains("${DB_PASSWORD:}"),
                "数据库口令应改为 ${DB_PASSWORD:} 占位符");
        assertTrue(yml.contains("${JWT_SECRET:}"),
                "JWT 密钥应改为 ${JWT_SECRET:} 占位符");
    }
}
