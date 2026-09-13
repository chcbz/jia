package cn.jia.core.deadline;

import java.util.regex.Pattern;

/** Stable, allowlisted timeout detail carried in the existing JsonResult data field. */
public record TimeoutErrorContract(String requestId, String failure, String dependency, boolean retryable) {
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    public TimeoutErrorContract {
        if (requestId == null || !VALID_REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("requestId must be a bounded correlation identifier");
        }
        try {
            SafeRequestTimeoutException.Failure.valueOf(failure);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("failure must be a supported timeout failure", exception);
        }
        try {
            SafeRequestTimeoutException.Dependency.valueOf(dependency);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("dependency must be a supported dependency", exception);
        }
    }
}
