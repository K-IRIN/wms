package com.wms.aspect;

import cn.hutool.json.JSONUtil;
import com.wms.annotation.OperationLog;
import com.wms.entity.SysOperationLog;
import com.wms.mapper.SysOperationLogMapper;
import com.wms.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 操作日志切面：拦截 @OperationLog 注解的方法，自动写入操作日志表。
 * 日志记录失败不影响主业务。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SysOperationLogMapper operationLogMapper;

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            saveLog(joinPoint, operationLog);
        } catch (Exception e) {
            log.warn("操作日志记录失败：{}", e.getMessage());
        }
        return result;
    }

    private void saveLog(ProceedingJoinPoint joinPoint, OperationLog operationLog) {
        SysOperationLog opLog = new SysOperationLog();
        UserContext.LoginUser user = UserContext.get();
        if (user != null) {
            opLog.setUserId(user.userId());
            opLog.setUsername(user.nickname() + "(" + user.username() + ")");
        } else {
            // 登录等未认证接口：尝试从参数里取用户名
            opLog.setUsername(tryGetUsernameFromArgs(joinPoint.getArgs()));
        }
        opLog.setOperation(operationLog.value());
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        opLog.setMethod(signature.getDeclaringType().getSimpleName() + "." + signature.getName());
        opLog.setIp(getClientIp());
        opLog.setParams(serializeArgs(joinPoint.getArgs()));
        operationLogMapper.insert(opLog);
    }

    /** 从参数中找 username 字段（登录/注册场景） */
    private String tryGetUsernameFromArgs(Object[] args) {
        if (args == null) {
            return "anonymous";
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            try {
                var method = arg.getClass().getMethod("getUsername");
                Object value = method.invoke(arg);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (NoSuchMethodException ignored) {
                // 该参数没有 getUsername 方法，继续找下一个
            } catch (Exception ignored) {
                // 反射调用失败，继续找下一个
            }
        }
        return "anonymous";
    }

    /** 序列化参数，过滤无法序列化的对象，脱敏密码，超长截断 */
    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        String json = Arrays.stream(args)
                .filter(arg -> !(arg instanceof HttpServletRequest)
                        && !(arg instanceof HttpServletResponse)
                        && !(arg instanceof MultipartFile))
                .map(arg -> {
                    try {
                        return JSONUtil.toJsonStr(arg);
                    } catch (Exception e) {
                        return String.valueOf(arg);
                    }
                })
                .collect(Collectors.joining(", "));
        // 密码脱敏
        json = json.replaceAll("(\"(?:password|oldPassword|newPassword)\"\s*:\s*\")[^\"]*(\")", "$1******$2");
        return json.length() > 2000 ? json.substring(0, 2000) + "..." : json;
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
