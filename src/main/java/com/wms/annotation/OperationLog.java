package com.wms.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解：标注在 Controller 方法上，AOP 自动记录操作人、内容、IP 到 sys_operation_log
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /** 操作内容描述，如"新增物料"、"审核入库单" */
    String value();
}
