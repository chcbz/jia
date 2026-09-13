package cn.jia.chat.ai;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** Applies an explicitly configured chat connection timeout; otherwise the provider transport remains unchanged. */
final class OpenAiChatTransportCustomizer implements OpenAiHttpClientBuilderCustomizer {
    private final Integer connectTimeoutMillis;

    OpenAiChatTransportCustomizer(AiProviderProperties properties) {
        properties.validate();
        this.connectTimeoutMillis = properties.getConnectBudget() == null
                ? null
                : Math.toIntExact(properties.getConnectBudget().toMillis());
    }

    @Override
    public void customize(SpringAiOpenAiHttpClient.Builder builder) {
        if (connectTimeoutMillis == null) {
            return;
        }
        builder.interceptor(chain -> {
            if (!isChatPath(chain.request().url().encodedPath())) {
                return chain.proceed(chain.request());
            }
            try {
                return chain.withConnectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                        .proceed(chain.request());
            } catch (IOException exception) {
                throw exception;
            }
        });
    }

    boolean hasConnectTimeoutOverride() {
        return connectTimeoutMillis != null;
    }

    Integer connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    static boolean isChatPath(String path) {
        return path != null && (path.endsWith("/chat/completions") || path.endsWith("/responses"));
    }
}
