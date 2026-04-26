package cn.jia.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 错误码模块注解
 * 
 * 用于标记错误码常量类，编译时注解处理器会自动收集并注册所有错误码
 * 
 * 使用方式：
 * {@code @ErrorCodeModule}
 * public class UserErrorConstants extends EsErrorConstants {
 *     public static final EsErrorConstants USER_NOT_FOUND = new EsErrorConstants("U001", "用户不存在");
 * }
 * 
 * @author chenzg
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ErrorCodeModule {
    /**
     * 模块名称，用于日志和调试
     */
    String value() default "";
}