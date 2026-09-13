package cn.jia.core.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonRequestPageTest {

    @Test
    void appliesUnifiedDefaultAndUpperBound() {
        JsonRequestPage<Object> page = new JsonRequestPage<>();
        assertEquals(JsonRequestPage.DEFAULT_PAGE_SIZE, page.getPageSize());

        page.setPageSize(Integer.MAX_VALUE);
        assertEquals(JsonRequestPage.MAX_PAGE_SIZE, page.getPageSize());

        page.setPageSize(JsonRequestPage.MAX_PAGE_SIZE + 1);
        assertEquals(JsonRequestPage.MAX_PAGE_SIZE, page.getPageSize());
    }

    @Test
    void normalizesNullAndNonPositiveSizesWithoutUnboundedFallback() {
        JsonRequestPage<Object> page = new JsonRequestPage<>();

        page.setPageSize(null);
        assertEquals(JsonRequestPage.DEFAULT_PAGE_SIZE, page.getPageSize());

        page.setPageSize(0);
        assertEquals(1, page.getPageSize());

        page.setPageSize(-10);
        assertEquals(1, page.getPageSize());
    }

    @Test
    void preservesValidRequestedSize() {
        JsonRequestPage<Object> page = new JsonRequestPage<>();
        page.setPageSize(37);
        assertEquals(37, page.getPageSize());
    }
}
