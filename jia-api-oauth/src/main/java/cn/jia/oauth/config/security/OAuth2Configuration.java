package cn.jia.oauth.config.security;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.JSONUtil;
import cn.jia.oauth.common.ErrorConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.config.annotation.configurers.ClientDetailsServiceConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerEndpointsConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerSecurityConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.redis.RedisTokenStore;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

@Slf4j
@Configuration
public class OAuth2Configuration {

	@Configuration
	@EnableResourceServer
	public static class ResourceServerConfig extends ResourceServerConfigurerAdapter {

		@Override
		public void configure(HttpSecurity http) throws Exception {
			http.csrf().disable().exceptionHandling().authenticationEntryPoint((request, response, authException) -> {
				log.warn(authException.getMessage(), authException);
				JSONResult<Object> result = new JSONResult<>();
				result.setMsg(authException.getMessage());
				result.setCode(ErrorConstants.UNAUTHORIZED);
				result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				response.setCharacterEncoding("UTF-8");
				response.setContentType("application/json; charset=utf-8");
				response.setHeader("Access-Control-Allow-Origin", "*");
				PrintWriter out = response.getWriter();
				out.print(JSONUtil.toJson(result));
			}).and().authorizeRequests().antMatchers("/oauth/clientid", "/oauth/client/register",
					"/oauth/sms/**", "/favicon.ico",
					"/**/*.html", "/**/*.js", "/**/*.css", "/**/*.jpg", "/**/*.png", "/**/*.ttf", "/**/*.woff").permitAll()
					.antMatchers(HttpMethod.OPTIONS).permitAll()
					.and().authorizeRequests().anyRequest().authenticated().and().httpBasic();
		}
		
		@Override
		public void configure(ResourceServerSecurityConfigurer resources) {
			resources.authenticationEntryPoint((request, response, authException) -> {
				log.warn(authException.getMessage(), authException);
				JSONResult<Object> result = new JSONResult<>();
				Throwable throwable = authException.getCause();
		        if (throwable instanceof InvalidTokenException) {
		        	result.setMsg("invalid token: " + authException.getMessage());
		        }else {
		        	result.setMsg(authException.getMessage());
		        }
				result.setCode(ErrorConstants.UNAUTHORIZED);
				result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				response.setCharacterEncoding("UTF-8");
				response.setContentType("application/json; charset=utf-8");
				response.setHeader("Access-Control-Allow-Origin", "*");
				PrintWriter out = response.getWriter();
				out.print(JSONUtil.toJson(result));
			});
		}
	}

	@Configuration
	@EnableAuthorizationServer
	protected static class AuthorizationServerConfiguration extends AuthorizationServerConfigurerAdapter {

		@Autowired
		private RedisConnectionFactory redisConnection;

		@Bean
		public TokenStore tokenStore() {
			return new RedisTokenStore(redisConnection);
		}

		@Autowired
		@Qualifier("authenticationManagerBean")
		private AuthenticationManager authenticationManager;
		
		/*@Autowired
	    private UserDetailsService userDetailsService;*/
		
		@Autowired
		private ClientDetailsService clientDetailsService;

		@Override
		public void configure(AuthorizationServerEndpointsConfigurer endpoints) {
			endpoints.tokenStore(tokenStore())/*.userDetailsService(userDetailsService)*/
					.authenticationManager(authenticationManager).allowedTokenEndpointRequestMethods(HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS)
					/*.pathMapping("/oauth/confirm_access")*/;
		}
		
		@Override
		public void configure(AuthorizationServerSecurityConfigurer security) {
			security.tokenKeyAccess("permitAll()").checkTokenAccess("permitAll()")
			.allowFormAuthenticationForClients();
		}

		@Override
		public void configure(ClientDetailsServiceConfigurer clients) throws Exception {
			clients.withClientDetails(clientDetailsService);
		}

	}

}
