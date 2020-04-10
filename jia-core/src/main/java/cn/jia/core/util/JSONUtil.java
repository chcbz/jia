package cn.jia.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;



/**
 * JSON转换工具类
 * 
 * @author penghuaiyi
 * @date 2014-04-04
 */
public class JSONUtil {

	/**
	 * 对象转换成JSON字符串
	 * 
	 * @param obj
	 *            需要转换的对象
	 * @return 对象的string字符
	 */
	public static String toJson(Object obj) {
		ObjectMapper mapper = new ObjectMapper();

        try {
			return mapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
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
		if(StringUtils.isEmpty(jsonString)) {
			return null;
		}
		ObjectMapper mapper = new ObjectMapper();
		//设置输入时忽略JSON字符串中存在而Java对象实际没有的属性
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		try {
			return mapper.readValue(jsonString, type);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * 将json字符串转换成list对象
	 * @param jsonString json字符串
	 * @param type 对象类型
	 * @return
	 */
	@SuppressWarnings("hiding")
	public static <T> List<T> jsonToList(String jsonString, TypeReference<List<T>> type) {
		
		if(StringUtils.isEmpty(jsonString)) {
			return null;
		}
		ObjectMapper mapper = new ObjectMapper();
		//设置输入时忽略JSON字符串中存在而Java对象实际没有的属性
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		try {
			return mapper.readValue(jsonString, type);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * 将json字符串转换成list集合
	 * 
	 * @param json
	 * @return
	 */
	public static List<Object> jsonToList(String json) {
		JSONArray obj = new JSONArray(json);
		return jsonToList(obj);
	}
	
	/**
	 * 将JSONArray对象转换成list集合
	 * 
	 * @param jsonArr
	 * @return
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
	 * @param json
	 * @return
	 */
	public static Map<String, Object> jsonToMap(String json) {
		if(StringUtils.isEmpty(json)) {
			return null;
		}
		JSONObject obj = new JSONObject(json);
		return jsonToMap(obj);
	}

	/**
	 * 将JSONObject转换成map对象
	 * 
	 * @param obj
	 * @return
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
	
	public static Object[] getArray(String str) {
		return jsonToList(str).toArray();
	}
	
	public static void main(String[] args) {
		/*Result result = new Result();
		result.setCode("o");
		result.setMsg("ok");
		String str = toJson(result);
		System.out.println(str);
		result = fromJson(str, Result.class);
		System.out.println(result);*/
		System.out.println(jsonToList("[{'a':'b'},{'a':'c'}]"));
		String ss = null;
		Map map = jsonToMap("{\"ss\":null, \"sa\": \"abc\"}");
		System.out.println(Objects.requireNonNull(map).get("ss") == null);
	}
}
