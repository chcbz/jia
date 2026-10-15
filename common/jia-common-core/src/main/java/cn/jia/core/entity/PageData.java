package cn.jia.core.entity;

import java.util.List;
import java.util.Objects;

/** Page metadata belongs inside data; total never travels through a JS number. */
public record PageData<T>(List<T> items, int pageNum, int pageSize, String total) {
    public PageData {
        Objects.requireNonNull(items, "items");
        if (pageNum < 1 || pageSize < 1 || total == null || !total.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid page metadata");
        }
        items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
    }
}
