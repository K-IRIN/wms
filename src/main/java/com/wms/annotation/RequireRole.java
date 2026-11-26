package com.wms.annotation;

import java.lang.annotation.*;

/**
 * 角色权限注解：标注在 Controller 方法或类上，由 {@code RequireRoleAspect} 校验当前登录用户的角色白名单。
 *
 * <p>用法：
 * <pre>
 *   &#64;RequireRole({"ADMIN", "DISPATCHER"})
 *   &#64;PostMapping("/create")
 *   public Result&lt;String&gt; create(...) { ... }
 * </pre>
 *
 * <p>命中白名单中任一角色即放行；管理员角色与其他角色一样需要在白名单中显式列出，
 * 保证"哪个角色能做哪件事"只有一个事实来源（注解本身）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    /** 允许访问的角色编码（sys_role.role_code），命中任一即放行，如 ADMIN / DISPATCHER */
    String[] value();

    /** 拒绝时的提示文案，默认为"无权限执行该操作" */
    String message() default "";
}
