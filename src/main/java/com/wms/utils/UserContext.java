package com.wms.utils;

import java.util.List;

/**
 * 当前登录用户上下文（ThreadLocal）
 * JwtInterceptor 在每次请求前写入，业务代码随时读取，请求结束清理
 */
public class UserContext {

    /**
     * 登录用户信息
     */
    public record LoginUser(Long userId, String username, String nickname, List<String> roles) {
        public boolean isAdmin() {
            return roles != null && roles.contains("ADMIN");
        }
    }

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser u = get();
        return u == null ? null : u.userId();
    }

    public static String getUsername() {
        LoginUser u = get();
        return u == null ? "anonymous" : u.username();
    }

    public static boolean isAdmin() {
        LoginUser u = get();
        return u != null && u.isAdmin();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
