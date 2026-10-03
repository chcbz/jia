package cn.jia.chat.archive.maintenance.http;

import java.util.Objects;

/** Exact success envelope for the controlled native protocol; no shared nullable metadata fields. */
public record ArchiveNativeResponse<T>(String code, T data, String msg, int status) {
    public ArchiveNativeResponse {
        if (!"E0".equals(code) || data == null || !"ok".equals(msg)
                || (status != 200 && status != 202)) {
            throw new IllegalArgumentException("Invalid archive native response envelope");
        }
    }

    public static <T> ArchiveNativeResponse<T> success(T data) {
        return new ArchiveNativeResponse<>("E0", Objects.requireNonNull(data, "data"), "ok", 200);
    }

    public static <T> ArchiveNativeResponse<T> accepted(T data) {
        return new ArchiveNativeResponse<>("E0", Objects.requireNonNull(data, "data"), "ok", 202);
    }
}
