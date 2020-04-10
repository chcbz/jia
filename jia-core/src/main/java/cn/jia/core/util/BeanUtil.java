package cn.jia.core.util;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class BeanUtil {
	/**
	 * * 将一个 Map 对象转化为一个 JavaBean * @param type 要转化的类型 * @param map 包含属性值的 map
	 * * @return 转化出来的 JavaBean 对象 * @throws IntrospectionException * 如果分析类属性失败
	 * * @throws IllegalAccessException * 如果实例化 JavaBean 失败 * @throws
	 * InstantiationException * 如果实例化 JavaBean 失败 * @throws
	 * InvocationTargetException * 如果调用属性的 setter 方法失败
	 * 
	 */
	@SuppressWarnings("rawtypes")
	public static Object convertMap(Class type, Map map)
			throws IntrospectionException, IllegalAccessException, InstantiationException, InvocationTargetException {
		BeanInfo beanInfo = Introspector.getBeanInfo(type); // 获取类属性
		Object obj = type.newInstance(); // 创建 JavaBean 对象

		// 给 JavaBean 对象的属性赋值
		PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
		for (PropertyDescriptor descriptor : propertyDescriptors) {
			String propertyName = descriptor.getName();

			if (map.containsKey(propertyName)) {
				// 下面一句可以 try 起来，这样当一个属性赋值失败的时候就不会影响其他属性赋值。
				Object value = map.get(propertyName);

				Object[] args = new Object[1];
				args[0] = value;

				descriptor.getWriteMethod().invoke(obj, args);
			}
		}
		return obj;
	}

	/**
	 * 将一个 JavaBean 对象转化为一个 Map
	 * 
	 * @param bean
	 *            要转化的JavaBean 对象
	 * @return 转化出来的 Map 对象
	 * @throws IntrospectionException
	 *             如果分析类属性失败
	 * @throws IllegalAccessException
	 *             如果实例化 JavaBean 失败
	 * @throws InvocationTargetException
	 *             如果调用属性的 setter 方法失败
	 */
	public static Map<String, Object> convertBean(Object bean)
			throws IntrospectionException, IllegalAccessException, InvocationTargetException {
		Class type = bean.getClass();
		Map<String, Object> returnMap = new HashMap<>();
		BeanInfo beanInfo = Introspector.getBeanInfo(type);

		PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
		for (PropertyDescriptor descriptor : propertyDescriptors) {
			String propertyName = descriptor.getName();
			if (!propertyName.equals("class")) {
				Method readMethod = descriptor.getReadMethod();
				Object result = readMethod.invoke(bean);
				if (result != null) {
					returnMap.put(propertyName, result);
				} else {
					returnMap.put(propertyName, "");
				}
			}
		}
		return returnMap;
	}
	
	/**
	 * 获取对象中的空属性
	 * @param source
	 * @return
	 */
	public static String[] getNullPropertyNames (Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        java.beans.PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> emptyNames = new HashSet<>();
        for(java.beans.PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) emptyNames.add(pd.getName());
        }
        String[] result = new String[emptyNames.size()];
        return emptyNames.toArray(result);
    }

	public static String[] getEmptyPropertyNames (Object source) {
		final BeanWrapper src = new BeanWrapperImpl(source);
		java.beans.PropertyDescriptor[] pds = src.getPropertyDescriptors();

		Set<String> emptyNames = new HashSet<>();
		for(java.beans.PropertyDescriptor pd : pds) {
			Object srcValue = src.getPropertyValue(pd.getName());
			if (srcValue == null || "".equals(srcValue)) emptyNames.add(pd.getName());

		}
		String[] result = new String[emptyNames.size()];
		return emptyNames.toArray(result);
	}

	/**
	 * 复制对象，排除空属性
	 * @param src
	 * @param target
	 */
    public static void copyPropertiesIgnoreNull(Object src, Object target){
		BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
	}

	/**
	 * 复制对象，排除空字符
	 * @param src
	 * @param target
	 */
	public static void copyPropertiesIgnoreEmpty(Object src, Object target){
		BeanUtils.copyProperties(src, target, getEmptyPropertyNames(src));
	}
    
    /**
     * 复制对象，排除空属性和指定属性
     * @param src
     * @param target
     * @param ignoreProperties
     */
    public static void copyPropertiesIgnoreNull(Object src, Object target, String... ignoreProperties){
    	String[] nullPropertys = getNullPropertyNames(src);
    	int nullPropertysLength = nullPropertys.length;
    	int ignorePropertiesLength = ignoreProperties.length;
    	ignoreProperties = Arrays.copyOf(ignoreProperties, nullPropertysLength + ignorePropertiesLength);
    	System.arraycopy(nullPropertys, 0, ignoreProperties, ignorePropertiesLength, nullPropertysLength);
        BeanUtils.copyProperties(src, target, ignoreProperties);
    }
}