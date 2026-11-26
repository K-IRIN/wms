package com.wms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j 接口文档配置，启动后访问 http://localhost:8080/api/doc.html
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI wmsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("WMS 智能仓储管理系统接口文档")
                        .description("基于 SpringBoot + Vue 的智能仓储管理系统（毕业设计）")
                        .version("1.0")
                        .contact(new Contact().name("K-IRIN")));
    }
}
