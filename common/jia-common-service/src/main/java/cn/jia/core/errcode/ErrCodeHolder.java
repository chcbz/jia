package cn.jia.core.errcode;

import cn.jia.core.exception.EsErrorConstants;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 错误码持有者 - 编译时零反射版本
 * 
 * 通过编译时注解处理器自动收集所有错误码，生成 ErrCodeRegistry
 * 在静态初始化时调用 ErrCodeRegistry.registerAll() 完成注册
 * 
 * GraalVM Native Image 兼容性：
 * - 零反射：无需在 reflect-config.json 中配置
 * - 编译时生成：所有错误码在编译期已知
 * 
 * @author chenzg
 */
@Slf4j
public class ErrCodeHolder {
    
    private static final Map<String, String> errEnumMap = new HashMap<>();

    /**
     * 注册错误码
     * 
     * @param code    错误码
     * @param message 错误消息
     */
    public static void register(String code, String message) {
        if (code != null && message != null) {
            errEnumMap.put(code, message);
        }
    }

    static {
        // 调用编译时生成的 ErrCodeRegistry 注册所有错误码
        // 这个类由 JiaErrorCodeProcessor 注解处理器在编译时自动生成
        try {
            Class<?> registryClass = Class.forName("cn.jia.core.errcode.ErrCodeRegistry");
            java.lang.reflect.Method method = registryClass.getMethod("registerAll");
            method.invoke(null);
            log.info("错误码注册完成，共注册 {} 个错误码", errEnumMap.size());
        } catch (ClassNotFoundException e) {
            // 编译时未生成 ErrCodeRegistry，单模块或无错误码
            log.debug("未找到 ErrCodeRegistry，请确保添加了 @ErrorCodeModule 注解");
        } catch (Exception e) {
            log.warn("错误码注册失败: {}", e.getMessage());
        }
    }

    public static String getMessage(EsErrorConstants errorConstants) {
        return errorConstants.getMessage();
    }

    public static String getMessage(String code) {
        return errEnumMap.get(code);
    }
}