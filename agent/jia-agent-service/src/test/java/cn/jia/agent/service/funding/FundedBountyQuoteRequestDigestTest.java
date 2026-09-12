package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentModelPreferenceDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundedBountyQuoteRequestDigestTest {
    @Test
    void quoteDigestBindsExplicitAgentModelContextAndMinimumPayout() {
        AgentTaskQuoteRequestDTO original = quote();
        byte[] digest = FundedBountyRequestDigest.quote("task-1", original);
        AgentTaskQuoteRequestDTO changed = quote();
        changed.setMinimumAcceptedPayoutMicro("2");
        assertFalse(Arrays.equals(digest, FundedBountyRequestDigest.quote("task-1", changed)));
        assertTrue(Arrays.equals(digest, FundedBountyRequestDigest.quote("task-1", quote())));
    }

    @Test
    void claimDigestBindsAllowQueueAndExactTaskVersion() {
        AgentTaskClaimRequestDTO original = claim(false, "0");
        byte[] digest = FundedBountyRequestDigest.claim("task-1", original);
        assertFalse(Arrays.equals(digest, FundedBountyRequestDigest.claim("task-1", claim(true, "0"))));
        assertFalse(Arrays.equals(digest, FundedBountyRequestDigest.claim("task-1", claim(false, "1"))));
    }

    private static AgentTaskQuoteRequestDTO quote() {
        AgentModelPreferenceDTO model = new AgentModelPreferenceDTO();
        model.setProvider("openai");
        model.setModel("configured-model");
        AgentTaskQuoteRequestDTO request = new AgentTaskQuoteRequestDTO();
        request.setAgentId("agt_00000000000000000000000000000001");
        request.setModelPreference(model);
        request.setContextRevision("0");
        request.setMinimumAcceptedPayoutMicro("1");
        return request;
    }

    private static AgentTaskClaimRequestDTO claim(boolean allowQueue, String version) {
        AgentTaskClaimRequestDTO request = new AgentTaskClaimRequestDTO();
        request.setAgentId("agt_00000000000000000000000000000001");
        request.setQuoteId("q_00000000000000000000000000000001");
        request.setTaskVersion(version);
        request.setAllowQueue(allowQueue);
        return request;
    }
}
