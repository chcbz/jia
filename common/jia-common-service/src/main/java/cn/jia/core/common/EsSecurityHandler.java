package cn.jia.core.common;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.context.EsContext;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.StringUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class EsSecurityHandler {
	private final static Logger logger = LoggerFactory.getLogger(EsSecurityHandler.class);
	
	public static String username() {
    	Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    	if(authentication != null && authentication.getPrincipal() instanceof UserDetails) {
    		return ((UserDetails)authentication.getPrincipal()).getUsername();
    	}else if(authentication instanceof OAuth2AuthenticationToken oauthToken) {
			OAuth2AuthenticatedPrincipal principal = oauthToken.getPrincipal();
			if (principal != null) {
				return principal.getName();
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
        	if(authentication instanceof OAuth2AuthenticationToken oauthToken) {
        		return oauthToken.getPrincipal().getAttribute("client_id");
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
		String username = StringUtil.valueOf(tokenMap.get("user_name"));
    	if(StringUtil.isNotEmpty(username)) {
    		RedisTemplate<String, Object> redisTemplate = SpringContextHolder.getBean("redisTemplate");
    		return StringUtil.valueOf(redisTemplate.opsForValue().get("clientId_"+ username));
    	}else {
    		return StringUtil.valueOf(tokenMap.get("client_id"));
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
		if(token != null && !"null".equals(token) && !"".equals(token)) {
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
    	if(StringUtil.isEmpty(clientId)) {
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
    		if(StringUtil.isNotEmpty(clientId)) {
        		return clientId;
        	} else {
        		String appcn = StringUtil.valueOf(request.getParameter("appcn"));
        		if(StringUtil.isEmpty(appcn)) {
        			String auth = request.getHeader("Authorization");
        			if(StringUtil.isNotEmpty(auth) && auth.startsWith("APPCN ")) {
        				appcn = auth.substring(6);
        			}
        		}
        		if(StringUtil.isNotEmpty(appcn)) {
        			RestTemplate restTemplate = SpringContextHolder.getBean("restTemplate");
					JsonResult<Map<String, Object>> result = restTemplate.getForObject("http://jia-api-oauth/oauth/clientid?appcn={appcn}", JsonResult.class, appcn);
        			if(result != null && EsErrorConstants.SUCCESS.getCode().equals(result.getCode())) {
        				return StringUtil.valueOf(result.getData());
        			}
        		}
        	}
    	} catch (Exception e) {
    		logger.error("clientId", e);
    		return null;
    	}
		return null;
    }

	/**
	 * 根据token获取当前用户上下文
	 *
	 * @param token token
	 * @return 用户上下文
	 */
	public static EsContext currentContext(String token) {
		return new EsContext();
	}
}
