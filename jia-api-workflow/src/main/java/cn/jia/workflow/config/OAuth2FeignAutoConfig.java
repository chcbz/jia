package cn.jia.workflow.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;

@Configuration
public class OAuth2FeignAutoConfig {
	@Bean
	public RequestInterceptor requestTokenBearerInterceptor() {
	    return requestTemplate -> {
			OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)
					SecurityContextHolder.getContext().getAuthentication().getDetails();

			requestTemplate.header("Authorization", "Bearer " + details.getTokenValue());
		};
	}
}
