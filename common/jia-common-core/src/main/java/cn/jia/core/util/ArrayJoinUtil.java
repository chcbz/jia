package cn.jia.core.util;

import org.mvel2.MVEL;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.MapVariableResolverFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数组连接操作工具类
 * 提供左连接、右连接、内连接、外连接等操作，并支持使用MVEL表达式进行计算
 */
public class ArrayJoinUtil {

    /**
     * 左连接操作
     *
     * @param left     左侧数组
     * @param right    右侧数组
     * @param leftKey  左侧数组的键函数
     * @param rightKey 右侧数组的键函数
     * @param merger   合并函数
     * @param <L>      左侧数组元素类型
     * @param <R>      右侧数组元素类型
     * @param <K>      键类型
     * @param <T>      结果类型
     * @return 连接后的结果列表
     */
    public static <L, R, K, T> List<T> leftJoin(List<L> left, List<R> right,
                                                Function<L, K> leftKey,
                                                Function<R, K> rightKey,
                                                JoinMerger<L, R, T> merger) {
        Map<K, R> rightMap = right.stream().collect(Collectors.toMap(rightKey, Function.identity(), (existing, replacement) -> existing));

        return left.stream().map(l -> {
            K key = leftKey.apply(l);
            R r = rightMap.get(key);
            return merger.merge(l, r);
        }).collect(Collectors.toList());
    }

    /**
     * 右连接操作
     *
     * @param left     左侧数组
     * @param right    右侧数组
     * @param leftKey  左侧数组的键函数
     * @param rightKey 右侧数组的键函数
     * @param merger   合并函数
     * @param <L>      左侧数组元素类型
     * @param <R>      右侧数组元素类型
     * @param <K>      键类型
     * @param <T>      结果类型
     * @return 连接后的结果列表
     */
    public static <L, R, K, T> List<T> rightJoin(List<L> left, List<R> right,
                                                 Function<L, K> leftKey,
                                                 Function<R, K> rightKey,
                                                 JoinMerger<L, R, T> merger) {
        return leftJoin(right, left, rightKey, leftKey, (r, l) -> merger.merge(l, r));
    }

    /**
     * 内连接操作
     *
     * @param left     左侧数组
     * @param right    右侧数组
     * @param leftKey  左侧数组的键函数
     * @param rightKey 右侧数组的键函数
     * @param merger   合并函数
     * @param <L>      左侧数组元素类型
     * @param <R>      右侧数组元素类型
     * @param <K>      键类型
     * @param <T>      结果类型
     * @return 连接后的结果列表
     */
    public static <L, R, K, T> List<T> innerJoin(List<L> left, List<R> right,
                                                 Function<L, K> leftKey,
                                                 Function<R, K> rightKey,
                                                 JoinMerger<L, R, T> merger) {
        Map<K, L> leftMap = left.stream().collect(Collectors.toMap(leftKey, Function.identity(), (existing, replacement) -> existing));

        return right.stream().map(r -> {
            K key = rightKey.apply(r);
            L l = leftMap.get(key);
            if (l != null) {
                return merger.merge(l, r);
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * 外连接操作（全外连接）
     *
     * @param left     左侧数组
     * @param right    右侧数组
     * @param leftKey  左侧数组的键函数
     * @param rightKey 右侧数组的键函数
     * @param merger   合并函数
     * @param <L>      左侧数组元素类型
     * @param <R>      右侧数组元素类型
     * @param <K>      键类型
     * @param <T>      结果类型
     * @return 连接后的结果列表
     */
    public static <L, R, K, T> List<T> outerJoin(List<L> left, List<R> right,
                                                 Function<L, K> leftKey,
                                                 Function<R, K> rightKey,
                                                 JoinMerger<L, R, T> merger) {
        Map<K, L> leftMap = left.stream().collect(Collectors.toMap(leftKey, Function.identity(), (existing, replacement) -> existing));
        Map<K, R> rightMap = right.stream().collect(Collectors.toMap(rightKey, Function.identity(), (existing, replacement) -> existing));

        Set<K> allKeys = new HashSet<>();
        allKeys.addAll(leftMap.keySet());
        allKeys.addAll(rightMap.keySet());

        return allKeys.stream().map(key -> {
            L l = leftMap.get(key);
            R r = rightMap.get(key);
            return merger.merge(l, r);
        }).collect(Collectors.toList());
    }

    /**
     * 多个数组左连接操作
     *
     * @param arrays       要连接的数组列表
     * @param keyFunctions 每个数组对应的键函数列表
     * @param merger       合并函数，用于合并所有数组的元素
     * @param <T>          结果类型
     * @return 连接后的结果列表
     */
    @SafeVarargs
    public static <T> List<T> multiLeftJoin(List<List<?>> arrays,
                                            List<Function<?, ?>> keyFunctions,
                                            MultiJoinMerger<T> merger,
                                            Object... dummy) { // 添加一个虚拟的varargs参数以使用@SafeVarargs
        if (arrays == null || arrays.isEmpty() || keyFunctions == null || keyFunctions.isEmpty()) {
            return Collections.emptyList();
        }

        if (arrays.size() != keyFunctions.size()) {
            throw new IllegalArgumentException("数组数量与键函数数量不匹配");
        }

        if (arrays.size() == 1) {
            return arrays.get(0).stream()
                    .map(item -> merger.merge(Collections.singletonList(item)))
                    .collect(Collectors.toList());
        }

        // 构建除第一个数组外的所有数组的映射
        List<Map<Object, Object>> rightMaps = new ArrayList<>();
        for (int i = 1; i < arrays.size(); i++) {
            Map<Object, Object> map = new HashMap<>();
            Function<Object, Object> keyFunc = (Function<Object, Object>) keyFunctions.get(i);
            for (Object item : arrays.get(i)) {
                Object key = keyFunc.apply(item);
                if (key != null) {
                    map.put(key, item);
                }
            }
            rightMaps.add(map);
        }

        // 执行左连接
        List<T> result = new ArrayList<>();
        Function<Object, Object> firstKeyFunc = (Function<Object, Object>) keyFunctions.get(0);
        for (Object leftItem : arrays.get(0)) {
            Object key = firstKeyFunc.apply(leftItem);
            List<Object> itemsToMerge = new ArrayList<>();
            itemsToMerge.add(leftItem);

            // 从其他数组中获取匹配项
            for (Map<Object, Object> rightMap : rightMaps) {
                itemsToMerge.add(rightMap.get(key));
            }

            result.add(merger.merge(itemsToMerge));
        }

        return result;
    }

    /**
     * 多个数组内连接操作
     *
     * @param arrays       要连接的数组列表
     * @param keyFunctions 每个数组对应的键函数列表
     * @param merger       合并函数，用于合并所有数组的元素
     * @param <T>          结果类型
     * @return 连接后的结果列表
     */
    @SafeVarargs
    public static <T> List<T> multiInnerJoin(List<List<?>> arrays,
                                             List<Function<?, ?>> keyFunctions,
                                             MultiJoinMerger<T> merger,
                                             Object... dummy) { // 添加一个虚拟的varargs参数以使用@SafeVarargs
        if (arrays == null || arrays.isEmpty() || keyFunctions == null || keyFunctions.isEmpty()) {
            return Collections.emptyList();
        }

        if (arrays.size() != keyFunctions.size()) {
            throw new IllegalArgumentException("数组数量与键函数数量不匹配");
        }

        if (arrays.size() == 1) {
            return arrays.get(0).stream()
                    .map(item -> merger.merge(Collections.singletonList(item)))
                    .collect(Collectors.toList());
        }

        // 为每个数组构建键到元素的映射
        List<Map<Object, Object>> maps = new ArrayList<>();
        for (int i = 0; i < arrays.size(); i++) {
            Map<Object, Object> map = new HashMap<>();
            Function<Object, Object> keyFunc = (Function<Object, Object>) keyFunctions.get(i);
            for (Object item : arrays.get(i)) {
                Object key = keyFunc.apply(item);
                if (key != null) {
                    map.put(key, item);
                }
            }
            maps.add(map);
        }

        // 找到所有数组都包含的键
        Set<Object> commonKeys = new HashSet<>(maps.get(0).keySet());
        for (int i = 1; i < maps.size(); i++) {
            commonKeys.retainAll(maps.get(i).keySet());
        }

        // 对每个公共键执行合并操作
        List<T> result = new ArrayList<>();
        for (Object key : commonKeys) {
            List<Object> itemsToMerge = new ArrayList<>();
            for (Map<Object, Object> map : maps) {
                itemsToMerge.add(map.get(key));
            }
            result.add(merger.merge(itemsToMerge));
        }

        return result;
    }

    /**
     * GROUP BY 操作
     *
     * @param list    要分组的列表
     * @param keyFunc 分组键函数
     * @param <T>     列表元素类型
     * @param <K>     分组键类型
     * @return 分组后的Map，键为分组键，值为具有相同键的元素列表
     */
    public static <T, K> Map<K, List<T>> groupBy(List<T> list, Function<T, K> keyFunc) {
        if (list == null || list.isEmpty()) {
            return new HashMap<>();
        }

        return list.stream().collect(Collectors.groupingBy(keyFunc));
    }

    /**
     * GROUP BY 操作，支持自定义分组值的收集方式
     *
     * @param list          要分组的列表
     * @param keyFunc       分组键函数
     * @param collectorFunc 分组值收集函数
     * @param <T>           列表元素类型
     * @param <K>           分组键类型
     * @param <V>           分组值类型
     * @return 分组后的Map，键为分组键，值为收集后的结果
     */
    public static <T, K, V> Map<K, V> groupBy(List<T> list, Function<T, K> keyFunc, Function<List<T>, V> collectorFunc) {
        if (list == null || list.isEmpty()) {
            return new HashMap<>();
        }

        Map<K, List<T>> grouped = list.stream().collect(Collectors.groupingBy(keyFunc));
        Map<K, V> result = new HashMap<>();

        for (Map.Entry<K, List<T>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), collectorFunc.apply(entry.getValue()));
        }

        return result;
    }

    /**
     * 使用MVEL表达式计算结果
     *
     * @param expression MVEL表达式
     * @param variables  变量映射
     * @return 表达式计算结果
     */
    public static Object eval(String expression, Map<String, Object> variables) {
        VariableResolverFactory factory = new MapVariableResolverFactory(variables);
        return MVEL.eval(expression, factory);
    }

    /**
     * 使用MVEL表达式计算结果并转换为指定类型
     *
     * @param expression MVEL表达式
     * @param variables  变量映射
     * @param toType     目标类型
     * @param <T>        结果类型
     * @return 表达式计算结果
     */
    public static <T> T eval(String expression, Map<String, Object> variables, Class<T> toType) {
        VariableResolverFactory factory = new MapVariableResolverFactory(variables);
        return MVEL.eval(expression, factory, toType);
    }

    /**
     * 合并函数接口
     *
     * @param <L> 左侧元素类型
     * @param <R> 右侧元素类型
     * @param <T> 结果类型
     */
    @FunctionalInterface
    public interface JoinMerger<L, R, T> {
        T merge(L left, R right);
    }

    /**
     * 多数组合并函数接口
     *
     * @param <T> 结果类型
     */
    @FunctionalInterface
    public interface MultiJoinMerger<T> {
        T merge(List<Object> items);
    }
}