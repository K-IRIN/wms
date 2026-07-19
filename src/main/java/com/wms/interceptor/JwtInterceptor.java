package com.wms.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.common.Result;
import com.wms.entity.SysUser;
import com.wms.service.AuthService;
import com.wms.service.SysUserService;
import com.wms.utils.JwtUtils;
import com.wms.utils.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * JWT 登录拦截器：解析 Token → 校验用户状态 → 写入 UserContext
 * 未登录或登录失效统一返回 401
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;
    private final SysUserService userService;
    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtils.parse(header.substring(7));
                Long userId = claims.get("userId", Long.class);
                SysUser user = userService.getById(userId);
                // 用户存在且状态为启用才放行
                if (user != null && user.getStatus() == 1) {
                    List<String> roles = authService.listRoleCodes(userId);
                    UserContext.set(new UserContext.LoginUser(userId, user.getUsername(), user.getNickname(), roles));
                    return true;
                }
            } catch (Exception ignored) {
                // Token 无效或过期，按未登录处理
            }
        }
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(401, "未登录或登录已过期")));
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
