package cn.jia.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;



/**
 * JSON转换工具类
 * 
 * @author penghuaiyi
 * @since 2014-04-04
 */
@Slf4j
public class JsonUtil {
	private static final ObjectMapper MAPPER;

	static {
		MAPPER = new ObjectMapper();
		//设置输入时忽略JSON字符串中存在而Java对象实际没有的属性
		MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		
		// 添加对Duration类型的支持
		MAPPER.registerModule(new JavaTimeModule());
	}

	/**
	 * 对象转换成JSON字符串
	 * 
	 * @param obj
	 *            需要转换的对象
	 * @return 对象的string字符
	 */
	public static String toJson(Object obj) {
        try {
			return MAPPER.writeValueAsString(obj);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} 
        return null;
	}

	/**
	 * JSON字符串转换成对象
	 * 
	 * @param jsonString
	 *            需要转换的字符串
	 * @param type
	 *            需要转换的对象类型
	 * @return 对象
	 */
	@SuppressWarnings("hiding")
	public static <T> T fromJson(String jsonString, Class<T> type) {
		if(StringUtil.isEmpty(jsonString)) {
			return null;
		}
		try {
			return MAPPER.readValue(jsonString, type);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}
	
	/**
	 * 将json字符串转换成list对象
	 * @param jsonString json字符串
	 * @param type 对象类型
	 * @return 对象列表
	 */
	public static <T> List<T> jsonToList(String jsonString, TypeReference<List<T>> type) {
		if(StringUtil.isEmpty(jsonString)) {
			return Collections.emptyList();
		}
		try {
			return MAPPER.readValue(jsonString, type);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return Collections.emptyList();
	}

	/**
	 * 将json字符串转换成list对象
	 * @param jsonString json字符串
	 * @param clazz 对象类型
	 * @return 对象列表
	 */
	public static <T> List<T> jsonToList(String jsonString, Class<T> clazz) {
		if(StringUtil.isEmpty(jsonString)) {
			return Collections.emptyList();
		}
		JavaType javaType = MAPPER.getTypeFactory().constructParametricType(List.class, clazz);
		try {
			return MAPPER.readValue(jsonString, javaType);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return Collections.emptyList();
	}

	/**
	 * 将json字符串转换成list集合
	 * 
	 * @param json 字符串
	 * @return 对象列表
	 */
	public static List<Object> jsonToList(String json) {
		JSONArray obj = new JSONArray(json);
		return jsonToList(obj);
	}
	
	/**
	 * 将JSONArray对象转换成list集合
	 * 
	 * @param jsonArr json列表
	 * @return 对象列表
	 */
	public static List<Object> jsonToList(JSONArray jsonArr) {
		List<Object> list = new ArrayList<>();
		for (int i=0;i<jsonArr.length();i++) {
			Object obj = jsonArr.opt(i);
			if (obj instanceof JSONArray) {
				list.add(jsonToList((JSONArray) obj));
			} else if (obj instanceof JSONObject) {
				list.add(jsonToMap((JSONObject) obj));
			} else {
				list.add(obj);
			}
		}
		return list;
	}

	/**
	 * 将json字符串转换成map对象
	 * 
	 * @param json 字符串
	 * @return map
	 */
	public static Map<String, Object> jsonToMap(String json) {
		if(StringUtil.isEmpty(json)) {
			return null;
		}
		JSONObject obj = new JSONObject(json);
		return jsonToMap(obj);
	}

	/**
	 * 将JSONObject转换成map对象
	 * 
	 * @param obj 对象
	 * @return map
	 */
	public static Map<String, Object> jsonToMap(JSONObject obj) {
		Set<?> set = obj.keySet();
		Map<String, Object> map = new HashMap<>(set.size());
		for (Object key : obj.keySet()) {
			Object value = obj.opt(key.toString());
			if (obj.isNull(key.toString())) {
				map.put(key.toString(), null);
			} else if (value instanceof JSONArray) {
				map.put(key.toString(), jsonToList((JSONArray) value));
			} else if (value instanceof JSONObject) {
				map.put(key.toString(), jsonToMap((JSONObject) value));
			} else {
				map.put(key.toString(), obj.opt(key.toString()));
			}

		}
		return map;
	}

	/**
	 * 将json字符串转换成List<Map<String, Object>>对象
	 * 
	 * @param jsonString json字符串
	 * @return List<Map<String, Object>>对象
	 */
	public static List<Map<String, Object>> jsonToListMap(String jsonString) {
		if (StringUtil.isEmpty(jsonString)) {
			return Collections.emptyList();
		}
		try {
			JavaType javaType = MAPPER.getTypeFactory().constructParametricType(List.class, Map.class);
			return MAPPER.readValue(jsonString, javaType);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return Collections.emptyList();
	}

	public static Object[] getArray(String str) {
		return jsonToList(str).toArray();
	}
}