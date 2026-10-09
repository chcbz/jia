package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandInboxV2ContractTest {
    @Test
    void sourceUsesSettledRelayClaimantNullsAndInboxUsesConsumerClaimant() {
        String source = AgentCommandInboxV2Mapper.SOURCE_SCOPE;
        String inbox = AgentCommandInboxV2Mapper.INBOX_SCOPE;
        for (String field : new String[]{"protocol_version=2", "tenant_id", "data_domain_id",
                "execution_pool_id", "admission_epoch", "producer_deployment_id", "module_id",
                "migration_epoch", "client_id"}) {
            assertTrue(source.contains(field), field);
        }
        for (String field : new String[]{"protocol_version=2", "tenant_id", "data_domain_id",
                "execution_pool_id", "admission_epoch", "producer_deployment_id", "module_id",
                "migration_epoch", "claimant_deployment_id", "claimant_worker_generation", "client_id"}) {
            assertTrue(inbox.contains(field), field);
        }
        assertFalse(source.contains("claimant_deployment_id"));
        assertFalse(source.contains("#{m.claimantDeploymentId}"));
        assertFalse(source.contains("#{m.claimantWorkerGeneration}"));
        assertTrue(source.contains("claimed_deployment_id IS NULL"));
        assertTrue(source.contains("claimed_worker_generation IS NULL"));
        assertFalse(inbox.contains("claimed_deployment_id"));
        for (String field : new String[]{"tenant_id", "data_domain_id", "execution_pool_id",
                "producer_deployment_id", "module_id", "client_id"}) {
            assertTrue(source.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(source.contains("OCTET_LENGTH(" + field + ")"), field);
        }
        assertTrue(inbox.contains("OCTET_LENGTH(claimant_deployment_id)"));
    }

    @Test
    void everyLockAndMutationIsFencedAndEveryStringCasIsByteExact() {
        for (Method method : AgentCommandInboxV2Mapper.class.getDeclaredMethods()) {
            if (method.getName().startsWith("lock")) {
                Select select = method.getAnnotation(Select.class);
                assertNotNull(select, method.getName());
                String sql = String.join(" ", select.value());
                assertTrue(sql.contains("FOR UPDATE"), method.getName());
                assertTrue(sql.contains("protocol_version=2"), method.getName());
                assertTrue(sql.contains("data_domain_id"), method.getName());
                assertTrue(sql.contains("execution_pool_id"), method.getName());
            }
            Update update = method.getAnnotation(Update.class);
            if (update != null) {
                String sql = String.join(" ", update.value());
                for (String field : new String[]{"protocol_version=2", "tenant_id", "data_domain_id",
                        "execution_pool_id", "admission_epoch", "producer_deployment_id",
                        "module_id", "migration_epoch", "client_id", "version="}) {
                    assertTrue(sql.contains(field), method.getName() + ":" + field);
                }
                for (String field : new String[]{"tenant_id", "data_domain_id", "execution_pool_id",
                        "producer_deployment_id", "module_id", "client_id"}) {
                    assertTrue(sql.contains("CAST(" + field + " AS BINARY)"), method.getName() + ":" + field);
                    assertTrue(sql.contains("OCTET_LENGTH(" + field + ")"), method.getName() + ":" + field);
                }
                assertTrue(sql.contains("consumer_name") || method.getName().equals("transitionDelivery"), method.getName());
                if (!method.getName().equals("transitionDelivery")) {
                    assertTrue(sql.contains("OCTET_LENGTH(consumer_name)")
                            || sql.contains("OCTET_LENGTH(ci.consumer_name)"), method.getName());
                    assertTrue(sql.contains("OCTET_LENGTH(message_id)")
                            || sql.contains("OCTET_LENGTH(ci.message_id)"), method.getName());
                    assertTrue(sql.contains("claimant_deployment_id"), method.getName());
                }
            }
        }
    }

    @Test
    void settlementCasIncludesEveryTokenIdentityAndLengthFence() throws Exception {
        Method method = AgentCommandInboxV2Mapper.class.getDeclaredMethod("settleInbox",
                cn.jia.agent.entity.AgentInboxClaimToken.class, String.class, String.class,
                Long.class, String.class, long.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value());
        for (String field : new String[]{"tenant_id", "data_domain_id", "execution_pool_id",
                "producer_deployment_id", "module_id", "claimant_deployment_id", "client_id",
                "consumer_name", "message_id", "event_id", "command_id", "lease_owner"}) {
            assertTrue(sql.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(sql.contains("OCTET_LENGTH(" + field + ")"), field);
        }
        for (String field : new String[]{"claimant_worker_generation", "admission_epoch",
                "migration_epoch", "delivery_id", "expires_at", "lease_until", "active_attempt"}) {
            assertTrue(sql.contains(field), field);
        }
    }

    @Test
    void deliverySettlementAndAcknowledgementCasFenceTheClaimantInboxByteExactly() throws Exception {
        for (String methodName : new String[]{"settleDeliveryFromClaim", "advanceAcknowledgement",
                "advanceSettledAcknowledgement"}) {
            Method method = java.util.Arrays.stream(AgentCommandInboxV2Mapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            String sql = String.join(" ", method.getAnnotation(Update.class).value());
            for (String field : new String[]{"tenant_id", "data_domain_id", "execution_pool_id",
                    "producer_deployment_id", "module_id", "claimant_deployment_id", "client_id",
                    "consumer_name", "message_id", "event_id", "command_id"}) {
                assertTrue(sql.contains("CAST(ci." + field + " AS BINARY)"), methodName + ":" + field);
                assertTrue(sql.contains("OCTET_LENGTH(ci." + field + ")"), methodName + ":" + field);
            }
            if (methodName.equals("advanceSettledAcknowledgement")) {
                assertTrue(sql.contains("ci.lease_owner IS NULL"), methodName);
                assertTrue(sql.contains("ci.lease_until IS NULL"), methodName);
            } else {
                assertTrue(sql.contains("CAST(ci.lease_owner AS BINARY)"), methodName);
                assertTrue(sql.contains("OCTET_LENGTH(ci.lease_owner)"), methodName);
            }
            for (String field : new String[]{"command_id", "active_message_id", "status"}) {
                assertTrue(sql.contains("CAST(" + field + " AS BINARY)"), methodName + ":delivery:" + field);
                assertTrue(sql.contains("OCTET_LENGTH(" + field + ")"), methodName + ":delivery:" + field);
            }
        }
    }

    @Test
    void inboxAcknowledgementCasFenceTokenAndStoredEvidenceByteExactly() throws Exception {
        for (String methodName : new String[]{"recordAcknowledgement", "settleInboxAfterAcknowledgement"}) {
            Method method = java.util.Arrays.stream(AgentCommandInboxV2Mapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            String sql = String.join(" ", method.getAnnotation(Update.class).value());
            for (String field : new String[]{"tenant_id", "data_domain_id", "execution_pool_id",
                    "producer_deployment_id", "module_id", "claimant_deployment_id", "client_id",
                    "consumer_name", "message_id", "event_id", "command_id", "lease_owner"}) {
                assertTrue(sql.contains("CAST(" + field + " AS BINARY)"), methodName + ":" + field);
                assertTrue(sql.contains("OCTET_LENGTH(" + field + ")"), methodName + ":" + field);
            }
        }
        Method settledRecord = java.util.Arrays.stream(AgentCommandInboxV2Mapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("recordSettledAcknowledgement"))
                .findFirst().orElseThrow();
        String settledSql = String.join(" ", settledRecord.getAnnotation(Update.class).value());
        for (String field : new String[]{"tenant_id", "data_domain_id", "execution_pool_id",
                "producer_deployment_id", "module_id", "claimant_deployment_id", "client_id",
                "consumer_name", "message_id", "event_id", "command_id"}) {
            assertTrue(settledSql.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(settledSql.contains("OCTET_LENGTH(" + field + ")"), field);
        }
        assertTrue(settledSql.contains("lease_owner IS NULL"));
        assertTrue(settledSql.contains("lease_until IS NULL"));
        Method settlement = AgentCommandInboxV2Mapper.class.getDeclaredMethod(
                "settleInboxAfterAcknowledgement", cn.jia.agent.entity.AgentInboxClaimToken.class,
                cn.jia.agent.entity.AgentConsumerInboxEntity.class, long.class);
        String settlementSql = String.join(" ", settlement.getAnnotation(Update.class).value());
        for (String field : new String[]{"ack_message_id", "ack_status", "ack_agent_id",
                "ack_runtime_installation_id", "ack_runtime_instance_id"}) {
            assertTrue(settlementSql.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(settlementSql.contains("OCTET_LENGTH(" + field + ")"), field);
            assertTrue(settledSql.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(settledSql.contains("OCTET_LENGTH(" + field + ")"), field);
        }
    }


    @Test
    void everyDeliveryCasFencesDurableBusinessIdentityByteExactly() throws Exception {
        String tokenScope = AgentCommandInboxV2Mapper.TOKEN_SOURCE_SCOPE;
        for (String field : new String[]{"task_id", "target_agent_id", "command_type"}) {
            assertTrue(tokenScope.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(tokenScope.contains("OCTET_LENGTH(" + field + ")"), field);
        }
        Method transition = java.util.Arrays.stream(AgentCommandInboxV2Mapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("transitionDelivery"))
                .findFirst().orElseThrow();
        String sql = String.join(" ", transition.getAnnotation(Update.class).value());
        for (String field : new String[]{"task_id", "target_agent_id", "command_type"}) {
            assertTrue(sql.contains("CAST(" + field + " AS BINARY)"), field);
            assertTrue(sql.contains("OCTET_LENGTH(" + field + ")"), field);
        }
    }

    @Test
    void manualDdlIsAdditiveAndHasGateColumns() throws Exception {
        String sql = Files.readString(Path.of(
                "../jia-agent-mapper/src/main/resources/db/agent-command-consumer-v2-manual-20260925.sql"));
        assertFalse(sql.toUpperCase().contains("UPDATE AGENT_CONSUMER_INBOX"));
        assertFalse(sql.toUpperCase().contains("DELETE FROM"));
        for (String field : new String[]{"protocol_version", "data_domain_id", "execution_pool_id",
                "admission_epoch", "producer_deployment_id", "module_id", "migration_epoch",
                "claimant_deployment_id", "claimant_worker_generation", "claim_delivery_version",
                "claim_inbox_version", "ack_delivery_version", "ack_message_id", "ack_status",
                "ack_at", "ack_agent_id", "ack_runtime_installation_id",
                "ack_runtime_instance_id", "chk_inbox_consumer_v2_scope",
                "chk_inbox_consumer_v2_ack_evidence"}) {
            assertTrue(sql.contains(field), field);
        }
    }
}
