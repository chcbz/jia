package cn.jia.core.util;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class EnumUtil {
    /**
     * 获取枚举类中的所有枚举值列表
     *
     * @param enumClass 枚举类
     * @return 枚举值列表
     * @param <T> 枚举类类型
     */
    public static <T extends Enum<T>> List<T> getAllEnumValues(Class<T> enumClass) {
        return Arrays.asList(enumClass.getEnumConstants());
    }

    /**
     * 根据枚举名称获取枚举值
     *
     * @param enumClass 枚举类
     * @param name 枚举名称
     * @return 枚举值
     * @param <T> 枚举类型
     */
    public static <T extends Enum<T>> T getEnumValueByName(Class<T> enumClass, String name) {
        return Enum.valueOf(enumClass, name);
    }

    /**
     * 检查枚举值是否存在
     *
     * @param enumClass 枚举类
     * @param name 枚举名称
     * @return 是否有效值
     * @param <T> 枚举类型
     */
    public static <T extends Enum<T>> boolean isValidEnumValue(Class<T> enumClass, String name) {
        try {
            Enum.valueOf(enumClass, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 获取枚举值名称列表
     *
     * @param enumClass 枚举类
     * @return 枚举名称列表
     * @param <T> 枚举类型
     */
    public static <T extends Enum<T>> List<String> getEnumValueNames(Class<T> enumClass) {
        return getAllEnumValues(enumClass).stream()
                .map(Enum::name)
                .collect(Collectors.toList());
    }
}
