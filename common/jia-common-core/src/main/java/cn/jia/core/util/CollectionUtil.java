package cn.jia.core.util;

import java.util.Collection;

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

    // 根据需要添加更多的实用方法
}