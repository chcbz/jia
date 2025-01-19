package cn.jia.base.config;

import io.undertow.UndertowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

@Component
public class GracefulShutdownConfig implements WebServerFactoryCustomizer<UndertowServletWebServerFactory> {
    @Value("${server.shutdown.timeout:30000}")
    private Integer shutdownTimeout;

    @Override
    public void customize(UndertowServletWebServerFactory factory) {
        factory.addBuilderCustomizers(builder -> {
            // 设置Undertow等待时间，确保请求被处理完毕
            builder.setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, shutdownTimeout);
        });
    }
}