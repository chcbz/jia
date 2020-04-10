package cn.jia.core.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import cn.jia.core.common.EsWebSocketHandler;
import cn.jia.core.interceptor.WebSocketHandshakeInterceptor;

/*@Configuration
@EnableWebMvc
@EnableWebSocket*/
public class WebSocketConfig extends WebMvcConfigurerAdapter implements WebSocketConfigurer {
	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(esWebSocketHandler(), "/webSocketServer")
				.addInterceptors(new WebSocketHandshakeInterceptor()).setAllowedOrigins("*");

		registry.addHandler(esWebSocketHandler(), "/sockjs/webSocketServer").setAllowedOrigins("*")
				.addInterceptors(new WebSocketHandshakeInterceptor()).withSockJS();
	}

	@Bean
	public WebSocketHandler esWebSocketHandler() {
		return new EsWebSocketHandler();
	}

}
