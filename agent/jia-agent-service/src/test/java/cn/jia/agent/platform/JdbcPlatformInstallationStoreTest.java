package cn.jia.agent.platform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jia.agent.platform.PlatformInstallationStore.*;

class JdbcPlatformInstallationStoreTest {
    @Test void terminalCandidateQueryIsBoundedAndIncludesReceiptTimeouts() {
        var query=JdbcPlatformInstallationStore.terminalCandidateQuery(null,1234,100);
        String sql=query.sql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("i.state='REQUESTED' AND i.result_sha256 IS NULL"));
        assertTrue(sql.contains("d.status IN ('DEAD','EXPIRED','CANCELLED','FAILED')"));
        assertTrue(sql.contains("d.status='SUCCEEDED' AND d.expires_at IS NOT NULL AND d.expires_at<=?"));
        assertTrue(sql.contains("ORDER BY i.created_at,i.installation_id LIMIT ?"));
        assertFalse(sql.contains("i.created_at>?"));
        assertArrayEquals(new Object[]{1234L,100},query.arguments());
    }

    @Test void terminalCandidateQueryUsesStrictCreatedAtInstallationKeyset() {
        var cursor=new ScanCursor(77,"psi_cursor");
        var query=JdbcPlatformInstallationStore.terminalCandidateQuery(cursor,1234,25);
        String sql=query.sql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("i.created_at>? OR (i.created_at=? AND i.installation_id>?)"));
        assertTrue(sql.contains("ORDER BY i.created_at,i.installation_id LIMIT ?"));
        assertArrayEquals(new Object[]{1234L,77L,77L,"psi_cursor",25},query.arguments());
    }

    @Test void terminalCandidateQueryRejectsUnboundedInputs() {
        assertThrows(IllegalArgumentException.class,()->JdbcPlatformInstallationStore.terminalCandidateQuery(null,-1,100));
        assertThrows(IllegalArgumentException.class,()->JdbcPlatformInstallationStore.terminalCandidateQuery(null,1,0));
        assertThrows(IllegalArgumentException.class,()->JdbcPlatformInstallationStore.terminalCandidateQuery(null,1,101));
    }

    @Test void verifiedResolutionQueryFiltersExactCurrentProofBeforeLimit() {
        var key=resolutionKey();
        var query=JdbcPlatformInstallationStore.verifiedCandidateQuery(key);
        String sql=query.sql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("i.tenant_id=? AND i.client_id=? AND i.owner_jiacn=? AND i.origin=?"));
        assertTrue(sql.contains("i.binding_id=? AND i.runtime_instance_id=? AND i.registration_hash=?"));
        assertTrue(sql.contains("i.state='SUCCEEDED' AND i.result_sha256 REGEXP '^[0-9a-f]{64}$'"));
        assertTrue(sql.contains("i.error_code IS NULL AND d.command_type='PLATFORM_SKILL_INSTALL'"));
        assertTrue(sql.contains("ORDER BY i.created_at DESC,i.installation_id DESC LIMIT 1"));
        assertEquals(11,query.arguments().length);
    }

    @Test void pendingResolutionQueryRequiresUnexpiredAllowedDeliveryAndIsBounded() {
        var query=JdbcPlatformInstallationStore.pendingCandidateQuery(resolutionKey(),1234);
        String sql=query.sql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("i.state='REQUESTED' AND i.result_sha256 IS NULL AND i.error_code IS NULL"));
        assertTrue(sql.contains("d.status IN ('PENDING','CONSUMED','SENT','RECEIVED','STARTED','SUCCEEDED')"));
        assertTrue(sql.contains("d.expires_at IS NOT NULL AND d.expires_at>?"));
        assertTrue(sql.contains("LIMIT 1"));
        assertEquals(1234L,query.arguments()[11]);
        assertThrows(IllegalArgumentException.class,()->JdbcPlatformInstallationStore.pendingCandidateQuery(resolutionKey(),-1));
    }


    @Test void historicalResolutionQueryUsesTheSameExactCurrentKeyAndBoundedOrder() {
        var query=JdbcPlatformInstallationStore.historicalCandidateQuery(resolutionKey());
        String sql=query.sql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("i.binding_id=?") || sql.contains("binding_id=?"));
        assertTrue(sql.contains("runtime_instance_id=? AND registration_hash=?"));
        assertTrue(sql.contains("ORDER BY created_at DESC,installation_id DESC LIMIT 1"));
        assertEquals(11,query.arguments().length);
        assertEquals(7L,query.arguments()[8]);
        assertEquals("runtime-a",query.arguments()[9]);
        assertArrayEquals(new byte[32],(byte[])query.arguments()[10]);
    }
    private static ResolutionKey resolutionKey() {
        return new ResolutionKey(new Scope("0","client-a","owner-a"),"PLATFORM_PROVISIONED","agent-a",
                "archive-maintainer","1.0.0","a".repeat(64),7,"runtime-a",new byte[32]);
    }
}
