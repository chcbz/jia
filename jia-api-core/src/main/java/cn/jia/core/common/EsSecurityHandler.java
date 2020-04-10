package cn.jia.core.common;

import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class EsSecurityHandler {
	private final static Logger logger = LoggerFactory.getLogger(EsSecurityHandler.class);
	
	public static String username() {
    	Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    	if(authentication != null && authentication.getPrincipal() instanceof UserDetails) {
    		return ((UserDetails)authentication.getPrincipal()).getUsername();
    	}else if(authentication instanceof OAuth2Authentication) {
    		Authentication userAuthentication = ((OAuth2Authentication) authentication).getUserAuthentication();
    		if(userAuthentication != null) {
    			return userAuthentication.getName();
    		}
    	}
    	return null;
    }
	
	/**
     * 获取登录客户ID
     * @return 客户端ID
     */
	public static String clientId() {
    	String username = username();
    	if(username != null) {
    		RedisTemplate<String, Object> redisTemplate = SpringContextHolder.getBean("redisTemplate");
				Object clientId = redisTemplate.opsForValue().get("clientId_"+ username);
    		return clientId == null ? username : clientId.toString();
    	}else {
    		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        	if(authentication instanceof OAuth2Authentication) {
        		return ((OAuth2Authentication) authentication).getOAuth2Request().getClientId();
        	}else {
        		return null;
        	}
    	}
    }
	
	/**
	 * 根据token获取客户端ID
	 * @param token token
	 * @return 客户端ID
	 */
	public static String clientId(String token) {
		RestTemplate restTemplate = SpringContextHolder.getBean("restTemplate");
		String tokenInfoUri = SpringContextHolder.getProperty("security.oauth2.resource.token-info-uri", String.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> tokenMap = restTemplate.getForObject(tokenInfoUri + "?token=" + token, Map.class);
		if(tokenMap == null) {
			return null;
		}
		String username = StringUtils.valueOf(tokenMap.get("user_name"));
    	if(StringUtils.isNotEmpty(username)) {
    		RedisTemplate<String, Object> redisTemplate = SpringContextHolder.getBean("redisTemplate");
    		return redisTemplate.opsForValue().get("clientId_"+ username).toString();
    	}else {
    		return StringUtils.valueOf(tokenMap.get("client_id"));
    	}
	}
    
    /**
     * 获取Jia平台Token
     * @return token
     */
    public static String jiaToken() {
    	RedisTemplate<String, Object> redisTemplate = SpringContextHolder.getBean("redisTemplate");
    	RestTemplate restTemplate = SpringContextHolder.getBean("restTemplate");

    	String token = String.valueOf(redisTemplate.opsForValue().get("jia-api-client-token"));
		if(token != null && !token.equals("null") && !token.equals("")) {
			return token;
		}
		
		Map<String, String> tokenParam = new HashMap<>();
    	tokenParam.put("grant_type", "client_credentials");
    	tokenParam.put("client_id", "jia_client");
    	tokenParam.put("client_secret", "jia_secret");
		@SuppressWarnings("unchecked")
		Map<String, Object> tokenMap = restTemplate.getForObject("http://jia-api-oauth/oauth/token?grant_type={grant_type}&client_id={client_id}&client_secret={client_secret}", Map.class, tokenParam);
		if(tokenMap != null) {
			token = String.valueOf(tokenMap.get("access_token"));
			Number expiresIn = (Number)tokenMap.get("expires_in");
			redisTemplate.opsForValue().set("jia-api-client-token", token, expiresIn.longValue()-60, TimeUnit.SECONDS);
			return token;
		}
		
		return null;
    }
    
    /**
     * 检查是否有ClientId
     * @param request HTTP请求
     * @throws AccessDeniedException 异常
     */
    public static String checkClientId(HttpServletRequest request) throws AccessDeniedException {
    	String clientId = clientId(request);
    	if(StringUtils.isEmpty(clientId)) {
    		throw new AccessDeniedException("Unable to get clientId");
    	}
    	return clientId;
    }
    
    /**
     * 获取ClientId
     * @param request HTTP请求
     * @return 客户端ID
     */
    @SuppressWarnings("unchecked")
	public static String clientId(HttpServletRequest request) {
    	try {
    		String clientId = clientId();
    		if(StringUtils.isNotEmpty(clientId)) {
        		return clientId;
        	} else {
        		String appcn = StringUtils.valueOf(request.getParameter("appcn"));
        		if(StringUtils.isEmpty(appcn)) {
        			String auth = request.getHeader("Authorization");
        			if(StringUtils.isNotEmpty(auth) && auth.startsWith("APPCN ")) {
        				appcn = auth.substring(6);
        			}
        		}
        		if(StringUtils.isNotEmpty(appcn)) {
        			RestTemplate restTemplate = SpringContextHolder.getBean("restTemplate");
					JSONResult<Map<String, Object>> result = restTemplate.getForObject("http://jia-api-oauth/oauth/clientid?appcn={appcn}", JSONResult.class, appcn);
        			if(result != null && EsErrorConstants.SUCCESS.equals(result.getCode())) {
        				return StringUtils.valueOf(result.getData());
        			}
        		}
        	}
    	} catch (Exception e) {
    		logger.error("clientId", e);
    		return null;
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
