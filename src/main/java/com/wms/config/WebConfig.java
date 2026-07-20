package com.wms.config;

import com.wms.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 拦截器 + 头像等上传文件的静态资源映射
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 登录、注册、验证码不需要 Token
                        "/auth/login", "/auth/register", "/auth/captcha",
                        // Knife4j 接口文档资源
                        "/doc.html", "/v3/api-docs/**", "/swagger-ui/**",
                        "/webjars/**", "/favicon.ico", "/service-worker.js",
                        // 上传文件（头像等）
                        "/uploads/**"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 运行目录下的 uploads 文件夹对外提供访问：/api/uploads/xxx
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}
