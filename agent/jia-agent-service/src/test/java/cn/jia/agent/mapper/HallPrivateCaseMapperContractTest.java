package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallPrivateCaseMapperContractTest {
    @Test
    void everyCaseReadIsExactOwnerScopedAndLocksUseForUpdate() {
        for (Class<?> type : new Class<?>[] {HallPrivateCaseMapper.class,
                HallCaseExecutionMapper.class}) {
            for (Method method : type.getMethods()) {
                Select select = method.getAnnotation(Select.class);
                if (select == null) continue;
                String sql = String.join(" ", select.value());
                assertTrue(sql.contains("CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"), method.getName());
                assertTrue(sql.contains("CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"), method.getName());
                assertTrue(sql.contains("CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)"), method.getName());
            }
        }
        Method lock = Arrays.stream(HallPrivateCaseMapper.class.getMethods())
                .filter(method -> method.getName().equals("lockExact")).findFirst().orElseThrow();
        assertTrue(String.join(" ", lock.getAnnotation(Select.class).value()).contains("FOR UPDATE"));
    }

    @Test
    void executionBindingRereadClearsSessionCacheAndBypassesSharedCache() throws Exception {
        Method read = HallCaseExecutionMapper.class.getMethod("findByExecution",
                String.class, String.class, String.class, String.class);
        Options options = read.getAnnotation(Options.class);
        assertNotNull(options);
        assertFalse(options.useCache());
        assertEquals(Options.FlushCachePolicy.TRUE, options.flushCache());
    }

    @Test
    void caseRevisionUpdateIsScopedCas() throws Exception {
        Method update = HallPrivateCaseMapper.class.getMethod("updateRevision",
                String.class, String.class, String.class, String.class,
                long.class, long.class, String.class, long.class);
        String sql = String.join(" ", update.getAnnotation(Update.class).value());
        assertTrue(sql.contains("revision=#{expectedRevision}"));
        assertTrue(sql.contains("CAST(case_id AS BINARY)=CAST(#{caseId} AS BINARY)"));
    }
}
