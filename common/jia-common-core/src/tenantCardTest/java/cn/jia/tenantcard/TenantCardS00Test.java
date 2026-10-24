package cn.jia.tenantcard;

import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantCardS00Test {
    @Test
    void acceptsCanonicalTenantId() {
        assertEquals("1", TenantId.of("1").value());
    }

    @Test
    void rejectsNonCanonicalTenantId() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("01"));
    }
}
