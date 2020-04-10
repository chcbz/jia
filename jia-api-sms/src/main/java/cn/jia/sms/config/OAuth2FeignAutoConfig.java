package cn.jia.sms.config;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.web.client.RestTemplate;

import feign.RequestInterceptor;
import feign.RequestTemplate;

@Configuration
public class OAuth2FeignAutoConfig {
	
	@Autowired
	private RedisTemplate<String, Object> redisTemplate;
	@Autowired
	private RestTemplate restTemplate;
	@Value("${security.oauth2.client.client-id}")
	private String clientId;
	@Value("${security.oauth2.client.client-secret}")
	private String clientSecret;
	@Value("${security.oauth2.client.access-token-uri}")
	private String accessTokenUri;
	
	@Bean
	public RequestInterceptor requestTokenBearerInterceptor() {
	    return new RequestInterceptor() {
	        @Override
	        public void apply(RequestTemplate requestTemplate) {
	        	Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
	            if(authentication != null && authentication.getDetails() instanceof OAuth2AuthenticationDetails) {
	            	requestTemplate.header("Authorization", "Bearer " + ((OAuth2AuthenticationDetails)authentication.getDetails()).getTokenValue());
	            }else {
	            	String token = String.valueOf(redisTemplate.opsForValue().get("jia-api-client-token"));
	        		if(token != null && token != "null" && token != "") {
	        			requestTemplate.header("Authorization", "Bearer " + token);
	        		}else {
	        			Map<String, String> tokenParam = new HashMap<String, String>();
		            	tokenParam.put("grant_type", "client_credentials");
		            	tokenParam.put("client_id", clientId);
		            	tokenParam.put("client_secret", clientSecret);
		        		@SuppressWarnings("unchecked")
						Map<String, Object> tokenMap = restTemplate.getForObject(accessTokenUri + "?grant_type={grant_type}&client_id={client_id}&client_secret={client_secret}", Map.class, tokenParam);
		        		if(tokenMap != null) {
		        			token = String.valueOf(tokenMap.get("access_token"));
		        			Number expiresIn = (Number)tokenMap.get("expires_in");
		        			redisTemplate.opsForValue().set("jia-api-client-token", token, expiresIn.longValue()-60, TimeUnit.SECONDS);
		        			requestTemplate.header("Authorization", "Bearer " + token);
		        		}
	        		}
	            }
	        }
	    };
	}
}
