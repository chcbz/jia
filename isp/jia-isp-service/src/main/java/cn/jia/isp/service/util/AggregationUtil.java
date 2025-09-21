package cn.jia.isp.service.util;

import java.util.List;
import java.util.Map;

/**
 * 聚合函数处理工具类
 * 提供使用Stream API处理各种聚合函数的功能
 */
public class AggregationUtil {

    /**
     * 判断对象是否为数字类型（包括字符串形式的数字）
     *
     * @param obj 待判断对象
     * @return 是否为数字类型
     */
    private static boolean isNumeric(Object obj) {
        if (obj instanceof Number) {
            return true;
        }
        if (obj instanceof String) {
            try {
                Double.parseDouble((String) obj);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 将对象解析为double数值
     *
     * @param obj 待解析对象
     * @return double数值
     */
    private static double parseNumber(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        if (obj instanceof String) {
            try {
                return Double.parseDouble((String) obj);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /**
     * 计算指定字段的总和
     *
     * @param data 数据列表
     * @param fieldName 字段名
     * @return 总和结果
     */
    public static Object sum(List<Map<String, Object>> data, String fieldName) {
        try {
            // 使用Stream API计算sum
            // 过滤出fieldName字段不为null且为数字类型的数据项，提取字段值并进行求和
            return data.stream()
                    .filter(map -> map.get(fieldName) != null && isNumeric(map.get(fieldName)))
                    .mapToDouble(map -> parseNumber(map.get(fieldName)))
                    .sum();
        } catch (Exception e) {
            // 如果处理失败，返回0
            return 0;
        }
    }

    /**
     * 计算指定字段的平均值
     *
     * @param data 数据列表
     * @param fieldName 字段名
     * @return 平均值结果
     */
    public static Object avg(List<Map<String, Object>> data, String fieldName) {
        try {
            // 使用Stream API计算avg
            return data.stream()
                    .filter(map -> map.get(fieldName) != null && isNumeric(map.get(fieldName)))
                    .mapToDouble(map -> parseNumber(map.get(fieldName)))
                    .average()
                    .orElse(0.0);
        } catch (Exception e) {
            // 如果处理失败，返回0
            return 0;
        }
    }

    /**
     * 计算指定字段的计数
     *
     * @param data 数据列表
     * @param fieldName 字段名
     * @return 计数结果
     */
    public static Object count(List<Map<String, Object>> data, String fieldName) {
        try {
            // 使用Stream API计算count
            return data.stream()
                    .filter(map -> map.get(fieldName) != null)
                    .count();
        } catch (Exception e) {
            // 如果处理失败，返回0
            return 0;
        }
    }

    /**
     * 计算指定字段的最大值
     *
     * @param data 数据列表
     * @param fieldName 字段名
     * @return 最大值结果
     */
    public static Object max(List<Map<String, Object>> data, String fieldName) {
        try {
            // 使用Stream API计算max
            return data.stream()
                    .filter(map -> map.get(fieldName) != null && isNumeric(map.get(fieldName)))
                    .mapToDouble(map -> parseNumber(map.get(fieldName)))
                    .max()
                    .orElse(0.0);
        } catch (Exception e) {
            // 如果处理失败，返回null
            return null;
        }
    }

    /**
     * 计算指定字段的最小值
     *
     * @param data 数据列表
     * @param fieldName 字段名
     * @return 最小值结果
     */
    public static Object min(List<Map<String, Object>> data, String fieldName) {
        try {
            // 使用Stream API计算min
            return data.stream()
                    .filter(map -> map.get(fieldName) != null && isNumeric(map.get(fieldName)))
                    .mapToDouble(map -> parseNumber(map.get(fieldName)))
                    .min()
                    .orElse(0.0);
        } catch (Exception e) {
            // 如果处理失败，返回null
            return null;
        }
    }
}