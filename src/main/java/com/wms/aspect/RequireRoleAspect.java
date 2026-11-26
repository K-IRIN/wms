package com.wms.aspect;

import com.wms.annotation.RequireRole;
import com.wms.common.BizException;
import com.wms.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 角色权限切面：拦截 {@link RequireRole} 注解标注的方法/类，校验当前登录用户的角色白名单。
 *
 * <p>为什么不用 Spring Security：整套安全框架会牵动登录、过滤器与全局异常处理，
 * 改动面远大于收益。本项目沿用 {@code UserContext} + 拦截器的轻量写法，
 * 用一个注解 + 一个切面把"后端按钮级授权"补齐，语义清晰且零侵入。
 *
 * <p>规则：
 * <ul>
 *   <li>方法级注解优先于类级注解</li>
 *   <li>未登录（无用户上下文）返回 401</li>
 *   <li>角色不匹配返回 403，并写 warn 日志（便于论文"权限测试"取证）</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class RequireRoleAspect {

    @Around("@annotation(com.wms.annotation.RequireRole) || @within(com.wms.annotation.RequireRole)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        RequireRole requireRole = resolveRequireRole(joinPoint);
        if (requireRole == null) {
            return joinPoint.proceed();
        }

        UserContext.LoginUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }

        List<String> roles = user.roles();
        String[] allowed = requireRole.value();
        boolean pass = roles != null && Arrays.stream(allowed).anyMatch(roles::contains);
        if (!pass) {
            String msg = requireRole.message().isBlank()
                    ? "无权限执行该操作，需要角色：" + String.join(" / ", allowed)
                    : requireRole.message();
            log.warn("越权拦截：用户 {}（角色 {}）访问 {}，需要角色 {}",
                    user.username(), roles, joinPoint.getSignature().toShortString(), Arrays.toString(allowed));
            throw new BizException(403, msg);
        }
        return joinPoint.proceed();
    }

    /** 方法级注解优先，其次类级注解 */
    private RequireRole resolveRequireRole(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RequireRole onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequireRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(joinPoint.getTarget().getClass(), RequireRole.class);
    }
}
