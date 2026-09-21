package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HallRequestDraftMapperContractTest {
    @Test
    void everyObjectReadUsesExactTenantClientOwnerPredicates() throws Exception {
        for (String method : new String[] {"findExact", "findByCreateKey", "findByDiscardKey", "listEditing"}) {
            Method target = Arrays.stream(HallRequestDraftMapper.class.getMethods())
                    .filter(candidate -> method.equals(candidate.getName())).findFirst().orElseThrow();
            Select select = target.getAnnotation(Select.class);
            String sql = String.join(" ", select.value());
            assertTrue(sql.contains("CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"), method);
            assertTrue(sql.contains("CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"), method);
            assertTrue(sql.contains("CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)"), method);
        }
    }

    @Test
    void privateRevisionSourceRequiresExactOwnerCommittedPrivateOutputAndActiveFile()
            throws Exception {
        Method target = HallRequestDraftMapper.class.getMethod("countPrivateCommittedOutput",
                String.class, String.class, String.class, String.class, String.class,
                String.class, int.class);
        String sql = String.join(" ", target.getAnnotation(Select.class).value());
        assertTrue(sql.contains("e.execution_mode='PRIVATE'"));
        assertTrue(sql.contains("e.execution_state='OUTPUT_COMMITTED'"));
        assertTrue(sql.contains("o.output_state='COMMITTED'"));
        assertTrue(sql.contains("o.publication_state='PENDING'"));
        assertTrue(sql.contains("f.state='ACTIVE'"));
        assertTrue(sql.contains("CAST(e.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)"));
    }

    @Test
    void createUsesUniqueReservationAndMutationsAreSingleStatementCas() throws Exception {
        Insert create = HallRequestDraftMapper.class.getMethod("reserveCreate",
                cn.jia.agent.entity.HallRequestDraftEntity.class).getAnnotation(Insert.class);
        assertTrue(String.join(" ", create.value()).contains("ON DUPLICATE KEY UPDATE draft_id=draft_id"));

        for (String method : new String[] {"replaceEditing", "discardEditing"}) {
            Method target = Arrays.stream(HallRequestDraftMapper.class.getMethods())
                    .filter(candidate -> method.equals(candidate.getName())).findFirst().orElseThrow();
            String sql = String.join(" ", target.getAnnotation(Update.class).value());
            assertTrue(sql.contains("state='EDITING' AND revision=#{expectedRevision}"), method);
            assertTrue(sql.contains("revision=revision+1"), method);
            assertTrue(sql.contains("CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)"), method);
        }
    }
}
