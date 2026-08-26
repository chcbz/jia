package cn.jia.agent.service;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;

/** Checked generation arithmetic shared by hosted preparation, repair, and publication. */
final class AgentHostedGeneration {
    private AgentHostedGeneration() {
    }

    static long requireNonNegative(Long generation) {
        if (generation == null || generation < 0) {
            fail();
        }
        return generation;
    }

    static long successor(long generation) {
        requireNonNegative(generation);
        if (generation == Long.MAX_VALUE) {
            fail();
        }
        return generation + 1;
    }

    static long requireSuccessor(long expectedGeneration, long nextGeneration) {
        long successor = successor(expectedGeneration);
        if (nextGeneration != successor) {
            fail();
        }
        return successor;
    }

    static void requireAdvanceable(String checkpoint, Long generation) {
        long current = requireNonNegative(generation);
        if (requiresSuccessor(checkpoint)) {
            successor(current);
        }
    }

    static void requireTransition(String expectedState, long expectedGeneration,
            long nextGeneration) {
        requireAdvanceable(expectedState, expectedGeneration);
        if (requiresSuccessor(expectedState)) {
            requireSuccessor(expectedGeneration, nextGeneration);
        } else if (nextGeneration != expectedGeneration) {
            fail();
        }
    }

    static boolean requiresSuccessor(String checkpoint) {
        return AgentHostedProfileState.PREPARED.equals(checkpoint)
                || AgentHostedProfileState.STAGED_DISABLED.equals(checkpoint)
                || AgentHostedProfileState.SUSPENDING.equals(checkpoint);
    }

    private static void fail() {
        throw new AgentBizException(AgentErrorConstants.AGENT_ERROR,
                "Hosted profile generation is invalid or exhausted");
    }
}
