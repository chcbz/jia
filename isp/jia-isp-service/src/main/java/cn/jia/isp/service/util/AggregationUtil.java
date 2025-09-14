package cn.jia.isp.service.util;

import org.mvel2.MVEL;

import java.util.List;
import java.util.Map;

/**
 * 聚合函数处理工具类
 * 提供使用MVEL处理各种聚合函数的功能
 */
public class AggregationUtil {

    /**
     * 计算指定字段的总和
     *
     * @param data 数据列表
     * @param fieldName 字段名
     * @return 总和结果
     */
    public static Object sum(List<Map<String, Object>> data, String fieldName) {
        try {
            // 使用MVEL表达式计算sum
            // 表达式含义：过滤出fieldName字段不为null的数据项，提取字段值并进行求和
            String expression = "data.?[get('" + fieldName + "') != null].![get('" + fieldName + "')].sum()";
            Map<String, Object> variables = Map.of("data", data);
            return MVEL.eval(expression, variables);
        } catch (Exception e) {
            // 如果MVEL处理失败，返回null
            return null;
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
            // 使用MVEL表达式计算avg
            String expression = "data.?[get('" + fieldName + "') != null].![get('" + fieldName + "')].average()";
            Map<String, Object> variables = Map.of("data", data);
            return MVEL.eval(expression, variables);
        } catch (Exception e) {
            // 如果MVEL处理失败，返回null
            return null;
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
            // 使用MVEL表达式计算count
            String expression = "data.?[get('" + fieldName + "') != null].size()";
            Map<String, Object> variables = Map.of("data", data);
            return MVEL.eval(expression, variables);
        } catch (Exception e) {
            // 如果MVEL处理失败，返回null
            return null;
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
            // 使用MVEL表达式计算max
            String expression = "data.?[get('" + fieldName + "') != null].![get('" + fieldName + "')].max()";
            Map<String, Object> variables = Map.of("data", data);
            return MVEL.eval(expression, variables);
        } catch (Exception e) {
            // 如果MVEL处理失败，返回null
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
            // 使用MVEL表达式计算min
            String expression = "data.?[get('" + fieldName + "') != null].![get('" + fieldName + "')].min()";
            Map<String, Object> variables = Map.of("data", data);
            return MVEL.eval(expression, variables);
        } catch (Exception e) {
            // 如果MVEL处理失败，返回null
            return null;
        }
    }
}