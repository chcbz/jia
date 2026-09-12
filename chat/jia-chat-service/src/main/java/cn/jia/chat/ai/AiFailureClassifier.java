package cn.jia.chat.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

final class AiFailureClassifier {
    private AiFailureClassifier() {
    }

    static AiProviderCallException sanitize(Throwable failure) {
        if (failure instanceof AiProviderCallException safe) {
            return safe;
        }
        return new AiProviderCallException(classify(failure));
    }

    static AiFailureCategory classify(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return AiFailureCategory.CANCELLED;
            }
            if (current instanceof HttpConnectTimeoutException || current instanceof ConnectException) {
                return AiFailureCategory.CONNECT_TIMEOUT;
            }
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return AiFailureCategory.TOTAL_TIMEOUT;
            }
            HttpStatusCode status = status(current);
            if (status != null) {
                if (status.value() == 401 || status.value() == 403) {
                    return AiFailureCategory.AUTHENTICATION;
                }
                if (status.value() == 429) {
                    return AiFailureCategory.RATE_LIMITED;
                }
                if (status.is4xxClientError()) {
                    return AiFailureCategory.PROVIDER_REJECTED;
                }
                if (status.is5xxServerError()) {
                    return AiFailureCategory.PROVIDER_UNAVAILABLE;
                }
            }
            String simpleName = current.getClass().getSimpleName();
            if ("AuthenticationException".equals(simpleName) || "UnauthorizedException".equals(simpleName)
                    || "PermissionDeniedException".equals(simpleName)) {
                return AiFailureCategory.AUTHENTICATION;
            }
            if ("RateLimitException".equals(simpleName)) {
                return AiFailureCategory.RATE_LIMITED;
            }
            if ("NonTransientAiException".equals(simpleName) || "BadRequestException".equals(simpleName)
                    || "UnprocessableEntityException".equals(simpleName)) {
                return AiFailureCategory.PROVIDER_REJECTED;
            }
            if ("TransientAiException".equals(simpleName) || current instanceof IOException
                    || "InternalServerException".equals(simpleName) || "ServiceUnavailableException".equals(simpleName)
                    || "OpenAIIoException".equals(simpleName)) {
                return AiFailureCategory.PROVIDER_UNAVAILABLE;
            }
        }
        return AiFailureCategory.UNKNOWN;
    }

    private static HttpStatusCode status(Throwable failure) {
        if (failure instanceof HttpStatusCodeException exception) {
            return exception.getStatusCode();
        }
        if (failure instanceof WebClientResponseException exception) {
            return exception.getStatusCode();
        }
        return null;
    }
}
