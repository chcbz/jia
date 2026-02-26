package cn.jia.core.common;

import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.Md5Util;
import cn.jia.core.util.StringUtil;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.support.RequestContext;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

/**
 * @author chc
 */
@Slf4j
public class EsHandler {
    /**
     * 非空判断，为空则报异常
     *
     * @param obj 对象
     */
	public static void assertNotNull(Object obj) {
		if (obj == null) throw new EsRuntimeException(EsErrorConstants.DATA_NOT_FOUND);
	}

	/**
	 * 检查数据绑定是否发生错误
	 *
	 * @param result
	 * @param message
	 * @return
	 */
	public static boolean checkBindingResultHasErrors(BindingResult result, StringBuffer message) {
		if (result.hasErrors()) {
			List<FieldError> errors = result.getFieldErrors();
			for (int i = 0; i < errors.size(); i++) {
				FieldError fieldError = errors.get(i);
				if (i != 0) {
					message.append(EsConstants.COMMA);
				}
				log.error("输入参数【" + fieldError.getField() + "】错误," + fieldError.getDefaultMessage());
				message.append(fieldError.getDefaultMessage());
			}
		}
		return result.hasErrors();
	}

	/**
	 * 检查数据绑定是否发生错误
	 *
	 * @param result
	 * @param model
	 * @return
	 */
	public static boolean checkBindingResultHasErrors(BindingResult result, Model model) {
		if (result.hasErrors()) {
			List<FieldError> errors = result.getFieldErrors();
			StringBuffer message = new StringBuffer();
			for (int i = 0; i < errors.size(); i++) {
				FieldError fieldError = errors.get(i);
				if (i != 0) {
					message.append(EsConstants.COMMA);
				}
				log.error("输入参数【" + fieldError.getField() + "】错误," + fieldError.getDefaultMessage());
				message.append(fieldError.getDefaultMessage());
			}
			model.addAttribute(EsErrorConstants.ERROR.getCode(), message.toString());
		}
		return result.hasErrors();
	}

	public static String getMessage(HttpServletRequest request, String key) {
		RequestContext requestContext = new RequestContext(request);
		return requestContext.getMessage(key);
	}

	public static String getMessage(HttpServletRequest request, String key, Object[] args) {
		RequestContext requestContext = new RequestContext(request);
		return requestContext.getMessage(key, args);
	}

	/**
	 * 获取日期数字形式，准确到秒
	 *
	 * @param date
	 * @return
	 */
	public static Long genTime(Date date) {
		String timeStr = String.valueOf(date.getTime());
		return Long.valueOf(timeStr.substring(0, timeStr.length() - 3));
	}

	/**
	 * 获取密码盐
	 *
	 * @return
	 */
	public static String createSalt() {
		String val = Md5Util.str2Base32Md5(String.valueOf(System.currentTimeMillis()));
		return val.substring(val.length() - 10);
	}

	/**
	 * 加盐处理密码,返回处理后的hash
	 *
	 * @param password
	 * @return 加盐处理后的hash
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 */
	public static String createHash(String password, String salt)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		return Md5Util.str2Base32Md5(password + salt);
	}

	/**
	 * 验证密码与 盐渍hash 是否匹配
	 * <p>
	 * return true 表示匹配,反之则false
	 * </p>
	 *
	 * @param password
	 * @param correctHash
	 * @return
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 */
	public static boolean validatePassword(String password, String salt, String correctHash)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		return StringUtil.equals(Md5Util.str2Base32Md5(password + salt), correctHash);
	}

	/**
	 * 获取系统配置文件
	 *
	 * @return
	 */
	public static Properties getSystemProperties() {
		Resource fileResource = new ClassPathResource("system.properties");
		Properties p = new Properties();
		try {
			p.load(fileResource.getInputStream());
		} catch (IOException e) {
			e.printStackTrace();
		}
		return p;
	}

	/**
	 * 将对象中的所有属性转成字符串格式输出
	 * @param data
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static void jsonValueToString(Object data){
		if(data instanceof Map){
			Iterator<Map.Entry<String, Object>> it = ((Map) data).entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry entry = it.next();
				Object value = entry.getValue();
				if(value instanceof Number){
					((Map) data).put(entry.getKey(), String.valueOf(value));
				}else{
					jsonValueToString(value);
					((Map) data).put(entry.getKey(), value);
				}
			}
		}else if(data instanceof List){
			List dd = new ArrayList();
			dd.addAll((List) data);
			((List) data).clear();
			for(Object value : dd){
				if(value instanceof Number){
					((List) data).add(String.valueOf(value));
				}else{
					jsonValueToString(value);
					((List) data).add(value);
				}
			}
		}
	}

	/**
	 * 根据Map生成RestTemplate传输的Entity
	 * @param params
	 * @return
	 */
	public static HttpEntity<String> genRestEntity(Object params) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		//  将提交的数据转换为String
		//  最好通过bean注入的方式获取ObjectMapper
		String value = JsonUtil.toJson(params);
		return new HttpEntity<>(value, headers);
	}

	public static HttpEntity<String> genRestEntity(Object params, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		//  将提交的数据转换为String
		//  最好通过bean注入的方式获取ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		String value = JsonUtil.toJson(params);
		return new HttpEntity<String>(value, headers);
	}
}
