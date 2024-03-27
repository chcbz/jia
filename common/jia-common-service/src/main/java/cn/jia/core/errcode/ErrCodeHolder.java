package cn.jia.core.errcode;

import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.ClassUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ErrCodeHolder {
    private static final Map<String, String> errEnumMap = new HashMap<>();

    static {
        List<String> classNames = ClassUtil.getClassNames("cn.jia");
        for (String className : classNames) {
            Class<?> clazz = ClassUtil.forName(className);
            if (clazz != null && EsErrorConstants.class.isAssignableFrom(clazz)) {
                List<Object> staticFieldValues = ClassUtil.getStaticFieldValues(clazz);
                for (Object field : staticFieldValues) {
                    if (field instanceof EsErrorConstants errEnum) {
                        errEnumMap.put(errEnum.getCode(), errEnum.getMessage());
                    }
                }
            }
        }
    }

    public static String getMessage(EsErrorConstants errorConstants) {
        // LocaleContextHolder.getLocale().toString()
        return errorConstants.getMessage();
    }

    public static String getMessage(String code) {
        // LocaleContextHolder.getLocale().toString()
        return errEnumMap.get(code);
    }
}
