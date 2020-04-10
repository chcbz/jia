package cn.jia.core.common;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.support.RequestContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.MD5Util;
import cn.jia.core.util.StringUtils;

public class EsHandler {
	private final static Logger logger = LoggerFactory.getLogger(EsHandler.class);

	/**
	 * 检查数据绑定是否发生错误
	 * 
	 * @param result
	 * @param resJson
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
				logger.error("输入参数【" + fieldError.getField() + "】错误," + fieldError.getDefaultMessage());
				message.append(fieldError.getDefaultMessage());
			}
		}
		return result.hasErrors();
	}

	/**
	 * 检查数据绑定是否发生错误
	 * 
	 * @param result
	 * @param resJson
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
				logger.error("输入参数【" + fieldError.getField() + "】错误," + fieldError.getDefaultMessage());
				message.append(fieldError.getDefaultMessage());
			}
			model.addAttribute(EsErrorConstants.ERROR, message.toString());
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
		String val = MD5Util.str2Base32MD5(String.valueOf(new Date().getTime()));
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
		return MD5Util.str2Base32MD5(password + salt);
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
		return StringUtils.equals(MD5Util.str2Base32MD5(password + salt), correctHash);
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
		headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
		//  将提交的数据转换为String
		//  最好通过bean注入的方式获取ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		String value;
		try {
			value = mapper.writeValueAsString(params);
			return new HttpEntity<String>(value, headers);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static HttpEntity<String> genRestEntity(Object params, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		//  将提交的数据转换为String
		//  最好通过bean注入的方式获取ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		String value;
		try {
			value = mapper.writeValueAsString(params);
			return new HttpEntity<String>(value, headers);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static void main(String[] args) {
		try {
			System.out.println(java.net.URLEncoder.encode("你的验证码是1234, 请在2分钟内输入！", "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}
}
