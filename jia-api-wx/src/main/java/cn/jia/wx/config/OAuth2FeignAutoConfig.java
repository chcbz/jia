package cn.jia.wx.config;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.jia.core.entity.JSONResult;
import cn.jia.wx.common.ErrorConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;

@Configuration
public class OAuth2FeignAutoConfig {
	
	@Autowired
	private RestTemplate restTemplate;
	
	@Bean
	public RequestInterceptor requestTokenBearerInterceptor() {
	    return new RequestInterceptor() {
	        @SuppressWarnings("unchecked")
			@Override
	        public void apply(RequestTemplate requestTemplate) {
	        	String userToken = "";
	        	//如果是微信传过来的消息，使用指定用户获取token，否则用admin获取token
	        	HttpServletRequest request = ((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();
	        	String curUser = String.valueOf(request.getSession().getAttribute("current_user"));
	        	if(!"".equals(curUser) && !"null".equals(curUser)) {
	        		//检查用户是否存在
        			Map<String, String> tokenParam = new HashMap<String, String>();
    	        	tokenParam.put("type", "openid");
    	        	tokenParam.put("key", curUser);
    	        	HttpHeaders headers = new HttpHeaders();
    			    HttpEntity<String> tokenEntity = new HttpEntity<>(null, headers);
    				JSONResult<Integer> userMsg = restTemplate.exchange("http://127.0.0.1:10010/user/check?type={type}&key={key}", HttpMethod.GET, tokenEntity, JSONResult.class, tokenParam).getBody();
    			    if(!ErrorConstants.SUCCESS.equals(userMsg.getCode()) || userMsg.getData().equals(0)) {
    			    	Map<String, String> userParam = new HashMap<String, String>();
						userParam.put("openid", curUser);
						userParam.put("jiacn", curUser);
						userParam.put("username", curUser);
						userParam.put("password", "123");
						HttpEntity<Map<String, String>> userEntity = new HttpEntity<Map<String, String>>(userParam, headers);
	    				userMsg = restTemplate.exchange("http://127.0.0.1:10010/user/create", HttpMethod.POST, userEntity, JSONResult.class).getBody();
    			    }
			    	try {
						Map<String, Object> userTokenMap = getToken("wx-"+curUser, "wxpwd");
						if(userTokenMap != null) {
							userToken = String.valueOf(userTokenMap.get("access_token"));
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
	        	}
	        	//非微信接口走默认权限通道
	        	if(StringUtils.isEmpty(userToken)) {
	        		OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)SecurityContextHolder.getContext().getAuthentication().getDetails();
	        		userToken = details.getTokenValue();
	        	}
	            requestTemplate.header("Authorization", "Bearer " + userToken);
	        }
	    };
	}
	
	@SuppressWarnings("unchecked")
	private Map<String, Object> getToken(String username, String password){
		MultiValueMap<String, String> tokenParam = new LinkedMultiValueMap<String, String>();
    	tokenParam.add("grant_type", "password");
    	tokenParam.add("username", username);
    	tokenParam.add("password", password);
    	HttpHeaders headers = new HttpHeaders();
    	headers.add(HttpHeaders.AUTHORIZATION, "Basic amlhX2NsaWVudDpqaWFfc2VjcmV0");
	    HttpEntity<MultiValueMap<String, String>> tokenEntity = new HttpEntity<>(tokenParam, headers);
		return restTemplate.postForObject("http://127.0.0.1:10010/oauth/token", tokenEntity, Map.class);
	}
}
