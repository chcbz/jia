package cn.jia.agent.config;

import cn.jia.agent.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2ExecutionEvidenceValidatorTest {
    static AgentRuntimeV2ExecutionTransactionTest.Fixture advanced() throws Exception {
        var f=new AgentRuntimeV2ExecutionTransactionTest.Fixture();f.start();f.reconnect();assertFalse(f.service.continueExecution(f.f.identity,f.socket,f.continuation(UUID.randomUUID().toString())).close());f.f.clock.value+=1000;assertFalse(f.service.renewExecution(f.f.identity,f.socket,f.renewal(UUID.randomUUID().toString())).close());return f;
    }
    @Test void historicalSuffixIsRealSharedSourceValidationAfterSettlementAndExpiry() throws Exception {
        var f=advanced();assertFalse(f.ack(f.ack("SUCCEEDED")).close());f.f.clock.value+=120000;var before=f.f.snapshot();f.verifyPrior();
        var source=new AgentRuntimeV2DispatchSource(f.p.dao,f.evidence);f.f.jdbc.locks.clear();new TransactionTemplate(f.manager).executeWithoutResult(s->source.lock(f.p.internal()));
        assertEquals(before,f.f.snapshot());for(String lock:f.f.jdbc.locks)for(String early:List.of("core_tenant_control","core_tenant_module_control","oauth_client","user_info","oauth_api_key","agent_runtime_v2_binding WHERE","core_deployment_registration"))assertFalse(lock.contains("FROM "+early),lock);
        assertTrue(f.f.jdbc.locks.stream().anyMatch(s->s.contains("FROM "+RECEIPT)));assertTrue(f.f.jdbc.locks.stream().anyMatch(s->s.contains("FROM "+FACT)));assertTrue(f.f.jdbc.locks.stream().anyMatch(s->s.contains("FROM "+GRANT)));
    }
    @Test void everyReachableReceiptFactGrantMappingAnchorAndProjectionMutationFailsClosed() throws Exception {
        for(String mutation:List.of(
            "UPDATE "+RECEIPT+" SET receipt_wire_sha256=CAST(REPEAT('x',32) AS BINARY(32)) WHERE status='STARTED'",
            "UPDATE "+RECEIPT+" SET request_fingerprint=CAST(REPEAT('x',32) AS BINARY(32)) WHERE status='STARTED'",
            "UPDATE "+FACT+" SET execution_origin_snapshot_sha256=CAST(REPEAT('x',32) AS BINARY(32)) WHERE fact_sequence=1",
            "UPDATE "+FACT+" SET holder_generation_snapshot_sha256=CAST(REPEAT('x',32) AS BINARY(32)) WHERE fact_sequence=1",
            "UPDATE "+FACT+" SET lease_revision=lease_revision+1 WHERE fact_type='RENEWED'",
            "UPDATE "+GRANT+" SET grant_wire_sha256=CAST(REPEAT('x',32) AS BINARY(32)) WHERE grant_kind='RENEWAL'",
            "UPDATE "+GRANT+" SET lease_until=lease_until+1 WHERE grant_kind='RENEWAL'",
            "UPDATE "+REQUEST+" SET request_fingerprint=CAST(REPEAT('x',32) AS BINARY(32)) WHERE request_kind='RENEWAL'",
            "UPDATE "+REQUEST+" SET previous_execution_grant_sha256=CAST(REPEAT('x',32) AS BINARY(32)) WHERE request_kind='RENEWAL'",
            "UPDATE "+REQUEST+" SET request_wire_bytes=NULL,previous_execution_grant_id=NULL,previous_execution_grant_sha256=NULL WHERE request_kind='RENEWAL'",
            "DELETE FROM "+REQUEST+" WHERE request_kind='RENEWAL'",
            "UPDATE "+ANCHOR+" SET execution_grant_id='11111111-1111-4111-8111-111111111111' WHERE holder_binding_generation>0",
            "UPDATE "+INBOX+" SET execution_lease_revision=execution_lease_revision+1",
            "UPDATE "+DELIVERY+" SET version=version+1",
            "DELETE FROM "+GRANT+" WHERE grant_kind='CONTINUATION'",
            "DELETE FROM "+ANCHOR+" WHERE holder_binding_generation>0")){
            var f=advanced();f.f.jdbc.update(mutation);var before=f.f.snapshot();assertThrows(SecurityException.class,f::verifyPrior,mutation);assertFalse(f.service.current(f.f.identity,f.socket,(byte[])f.grant().get("grant_wire_bytes")),mutation);assertEquals(before,f.f.snapshot());
        }
    }
    @Test void immutableRawMappingIsNotReconstructedFromMatchingFingerprintOrForeignHolder() throws Exception {
        var f=advanced();f.f.jdbc.update("UPDATE "+REQUEST+" SET requested_holder_generation_fact_id=(SELECT dispatch_generation_fact_id FROM "+INTERNAL+") WHERE request_kind='RENEWAL'");var before=f.f.snapshot();assertThrows(SecurityException.class,f::verifyPrior);assertEquals(before,f.f.snapshot());
    }
}
