package cn.jia.core.entity;

import java.util.List;

/** A page uses the same HTTP envelope, never a second root-level protocol. */
public final class JsonResultPage<T> extends JsonResult<PageData<T>> {
    private JsonResultPage(PageData<T> page) {
        super(page);
    }

    public static <T> JsonResultPage<T> page(List<T> items, int pageNum, int pageSize, long total) {
        if (total < 0) throw new IllegalArgumentException("Invalid page total");
        return new JsonResultPage<>(new PageData<>(items, pageNum, pageSize, Long.toString(total)));
    }
}
