package cn.jia.core.util;

import java.util.Collection;
import java.util.function.Function;

/**
 * 提供集合操作的实用方法。
 */
public class CollectionUtil {

    /**
     * 检查提供的集合是否为空或者为null。
     *
     * @param collection 要检查的集合
     * @return 如果集合为空或者为null，则返回true，否则返回false
     */
    public static boolean isNullOrEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * 检查提供的集合是否不为空且不为null。
     *
     * @param collection 要检查的集合
     * @return 如果集合不为空且不为null，则返回true，否则返回false
     */
    public static boolean isNotNullOrEmpty(Collection<?> collection) {
        return !isNullOrEmpty(collection);
    }

    /**
     * 获取列表的第一个元素，如果列表为空或者为null，则返回null
     *
     * @param collection 集合
     * @return 列表的第一个元素
     * @param <T> 返回值的类型
     */
    public static <T> T findFirst(Collection<T> collection) {
        return isNullOrEmpty(collection) ? null : collection.iterator().next();
    }
}