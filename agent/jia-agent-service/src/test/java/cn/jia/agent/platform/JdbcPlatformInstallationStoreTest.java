package cn.jia.agent.platform;

import org.junit.jupiter.api.Test;
import java.util.List;
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
        assertFalse(sql.contains("FOR UPDATE"));
        assertTrue(JdbcPlatformInstallationStore.verifiedCandidateQuery(key,true).sql().contains("FOR UPDATE"));
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
    @Test void reclaimReferenceProofUsesExactScopeBoundedCurrentLockingRead() {
        var current = new Installation("psi-current", new Scope("0","client-a","owner-a"),
                "actor-a","key-current","a".repeat(64),"agent-a",7,"runtime-a",new byte[32],
                "archive-maintainer","1.0.0","b".repeat(64),"challenge-current","command-current",
                "SUCCEEDED","c".repeat(64),null,1,2);
        var query = JdbcPlatformInstallationStore.currentGrantReferenceQuery(current,"psi-old");
        String sql = query.sql().replaceAll("\\s+"," ").strip();
        assertTrue(sql.startsWith("SELECT grant_ref FROM archive_execution_grant"));
        assertTrue(sql.contains("tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?"));
        assertTrue(sql.contains("installation_ref=? AND state IN ('ACTIVE','READ_ONLY')"));
        assertTrue(sql.endsWith("LIMIT 1 FOR SHARE"));
        assertFalse(sql.contains("COUNT("));
        assertArrayEquals(new Object[]{"0","client-a","owner-a","agent-a","psi-old"},query.arguments());
    }

    @Test void reclaimProgressUsesBoundedUniqueKeysetAndNeverOfferedPriority() {
        var current = new Installation("psi-current", new Scope("0","client-a","owner-a"),
                "actor-a","key-current","a".repeat(64),"agent-a",7,"runtime-a",new byte[32],
                "archive-maintainer","1.0.0","b".repeat(64),"challenge-current","command-current",
                "SUCCEEDED","c".repeat(64),null,1,50);
        var fresh = JdbcPlatformInstallationStore.reclaimCandidatesQuery(current,"SUCCEEDED",null,32);
        assertTrue(fresh.sql().contains("i.state=?"));
        assertFalse(fresh.sql().contains("IN ('SUCCEEDED','RECLAIMABLE')"));
        assertTrue(fresh.sql().contains("ORDER BY i.created_at,i.installation_id LIMIT ? FOR UPDATE"));
        var after = JdbcPlatformInstallationStore.reclaimCandidatesQuery(current,"RECLAIMABLE",new ScanCursor(7,"psi-032"),31);
        assertTrue(after.sql().contains("i.created_at>? OR (i.created_at=? AND i.installation_id>?)"));
        assertEquals(31,after.arguments()[after.arguments().length-1]);
        assertThrows(IllegalArgumentException.class,()->JdbcPlatformInstallationStore.reclaimCandidatesQuery(current,"RECLAIMABLE",null,33));
    }

    @Test void reclaimReceiptBatchIsDurableBoundedAndStrictlyParsed() {
        var batch=new JdbcPlatformInstallationStore.ReclaimBatch(1, new ScanCursor(7,"psi-032"),List.of("psi-033","psi-034"));
        String json=JdbcPlatformInstallationStore.encodeReclaimBatch(batch);
        assertEquals(batch,JdbcPlatformInstallationStore.decodeReclaimBatch(json));
        assertThrows(IllegalStateException.class,()->JdbcPlatformInstallationStore.decodeReclaimBatch("{\"formatVersion\":1,\"items\":[],\"cursor\":{\"installationId\":\"psi-a\"}}"));
        assertThrows(IllegalStateException.class,()->JdbcPlatformInstallationStore.decodeReclaimBatch("{\"formatVersion\":1,\"items\":[],\"cursor\":{\"createdAt\":1.5,\"installationId\":\"psi-a\"}}"));
        assertThrows(IllegalStateException.class,()->JdbcPlatformInstallationStore.decodeReclaimBatch("{\"formatVersion\":1,\"items\":[],\"cursor\":{\"createdAt\":1,\"installationId\":\"psi-a\",\"extra\":true}}"));
        assertThrows(IllegalStateException.class,()->JdbcPlatformInstallationStore.decodeReclaimBatch("{\"formatVersion\":1,\"items\":[\"psi-a\",\"psi-a\"],\"cursor\":null}"));
        assertThrows(IllegalStateException.class,()->JdbcPlatformInstallationStore.decodeReclaimBatch("{\"formatVersion\":1,\"items\":[\"../foreign\"],\"cursor\":null}"));
        assertThrows(IllegalStateException.class,()->JdbcPlatformInstallationStore.decodeReclaimBatch("{\"formatVersion\":2,\"items\":[],\"cursor\":null}"));
    }

    private static ResolutionKey resolutionKey() {
        return new ResolutionKey(new Scope("0","client-a","owner-a"),"PLATFORM_PROVISIONED","agent-a",
                "archive-maintainer","1.0.0","a".repeat(64),7,"runtime-a",new byte[32]);
    }
}
