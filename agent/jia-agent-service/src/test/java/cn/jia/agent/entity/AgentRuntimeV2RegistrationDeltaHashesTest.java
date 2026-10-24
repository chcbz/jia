package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static org.junit.jupiter.api.Assertions.*;

/** Independent Node/OpenSSL vectors from the parent-owned §15.3 golden. Not an authorization fixture. */
class AgentRuntimeV2RegistrationDeltaHashesTest {
    @Test void threeIndependentGoldenInputsIncludeNullAndUtf8() {
        Map<String,Object> op=row("drain_operation_id","11111111-1111-4111-8111-111111111111","operation_scope","MODULE_QUIESCE","tenant_id","1","module_id","agent","operator_id","operator-test","audit_reason","测试排空：保留原事实","source_tenant_operator_id",null,"source_tenant_idempotency_key",null,"tenant_state","ENABLED","tenant_control_revision",9L,"tenant_session_epoch",3L,"tenant_admission_epoch",4L,"tenant_drain_epoch",0L,"module_mode_state","QUIESCING","module_mode_revision",8L,"module_migration_epoch",2L,"module_schema_version",2L,"module_mapping_digest",null,"tenant_cutoff_at",null,"tenant_deadline_at",null,"module_cutoff_at",1000L,"module_deadline_at",10000L,"cutoff_at",1000L,"deadline_at",10000L,"issued_at",1500L);
        String operationHash="27353c5593d3dee87c30f7131c2543bd2800cfd69f4bb88ef818cb3ff2769ac8";
        verify("CYF-RUNTIME-V2-DRAIN-OPERATION-V1",AgentRuntimeV2RegistrationDeltaHashes.OPERATION,op,281,operationHash,AgentRuntimeV2RegistrationDeltaHashes.operation(op));
        String subject="5a8567e1c9c076fab680c4c9240f01bb110f982609f63e014ff89a23eb2e8151";
        Map<String,Object> completion=row("drain_operation_id",op.get("drain_operation_id"),"runtime_binding_id","22222222-2222-4222-8222-222222222222","tenant_id","1","module_id","agent","registration_subject_sha256",HexFormat.of().parseHex(subject),"registration_authority_id","44444444-4444-4444-8444-444444444444","runtime_instance_id","33333333-3333-4333-8333-333333333333","process_public_key_sha256",HexFormat.of().parseHex("ef1f7d8ef0ab699b890076ef20201662cde62fccd4571b98c263d73c2d1d574f"),"claimant_deployment_id","claimant-test","claimant_worker_generation",7L,"frozen_connected_fact_id","55555555-5555-4555-8555-555555555555","frozen_connected_fact_sha256",HexFormat.of().parseHex("33".repeat(32)),"frozen_registration_receipt_id","66666666-6666-4666-8666-666666666666","frozen_registration_receipt_sha256",HexFormat.of().parseHex("44".repeat(32)),"operation_snapshot_sha256",HexFormat.of().parseHex(operationHash),"issued_at",1600L);
        verify("CYF-RUNTIME-V2-DRAIN-COMPLETION-V1",AgentRuntimeV2RegistrationDeltaHashes.COMPLETION,completion,662,"3a9464a177e5e02d5b55e4b4d64ce5ddfcb96f8898c199daad9f7ac1272fc0f3",AgentRuntimeV2RegistrationDeltaHashes.completion(completion));
        Map<String,Object> context=row("registration_subject_sha256",HexFormat.of().parseHex(subject),"runtime_binding_id",completion.get("runtime_binding_id"),"prior_registration_receipt_id",completion.get("frozen_registration_receipt_id"),"prior_registration_receipt_sha256",completion.get("frozen_registration_receipt_sha256"),"runtime_instance_id",completion.get("runtime_instance_id"),"provisional_session_fence","cccccccc-cccc-4ccc-8ccc-cccccccccccc","issuer_socket_owner_instance_id","dddddddd-dddd-4ddd-8ddd-dddddddddddd","issuer_socket_owner_epoch",0L);
        verify("CYF-RUNTIME-V2-CHALLENGE-REQUEST-CONTEXT-V1",AgentRuntimeV2RegistrationDeltaHashes.CONTEXT,context,388,"bdc4092349a63fb2759899257bfeaedaf4f603744edaf8e2ba106cd728f5e0c3",AgentRuntimeV2RegistrationDeltaHashes.context(context));
        byte[] original=AgentRuntimeV2RegistrationDeltaHashes.context(context);context.put("registration_authority_revision",99L);context.put("registration_authority_sha256",new byte[32]);assertArrayEquals(original,AgentRuntimeV2RegistrationDeltaHashes.context(context));
        op.remove("source_tenant_operator_id");assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationDeltaHashes.operation(op));
    }
    private static void verify(String domain,List<String> order,Map<String,Object> row,int length,String hash,byte[] actual) {
        assertEquals(length,AgentRuntimeV2RegistrationFingerprints.lp(AgentRuntimeV2RegistrationDeltaHashes.fields(domain,order,row)).length);
        assertEquals(hash,HexFormat.of().formatHex(actual));
    }
}
