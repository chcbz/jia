package cn.jia.chat.ai;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** Limits only chat connection establishment; audio and other OpenAI clients retain their own boundaries. */
final class OpenAiChatTransportCustomizer implements OpenAiHttpClientBuilderCustomizer {
    private final int connectTimeoutMillis;

    OpenAiChatTransportCustomizer(AiProviderProperties properties) {
        long millis = properties.getConnectBudget().toMillis();
        this.connectTimeoutMillis = Math.toIntExact(millis);
    }

    @Override
    public void customize(SpringAiOpenAiHttpClient.Builder builder) {
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

    static boolean isChatPath(String path) {
        return path != null && (path.endsWith("/chat/completions") || path.endsWith("/responses"));
    }
}
