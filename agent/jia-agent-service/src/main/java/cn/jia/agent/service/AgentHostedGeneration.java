package cn.jia.agent.service;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;

import java.util.Set;

/** Checked generation arithmetic shared by hosted preparation, repair, and publication. */
final class AgentHostedGeneration {
    private static final Set<HostedEdge> ALLOWED_EDGES = Set.of(
            new HostedEdge(AgentHostedProfileState.PREPARED, AgentHostedProfileState.STAGED_DISABLED),
            new HostedEdge(AgentHostedProfileState.STAGED_DISABLED, AgentHostedProfileState.FILE_ENABLED),
            new HostedEdge(AgentHostedProfileState.FILE_ENABLED, AgentHostedProfileState.ACTIVE),
            new HostedEdge(AgentHostedProfileState.ACTIVE, AgentHostedProfileState.SUSPENDING),
            new HostedEdge(AgentHostedProfileState.SUSPENDING, AgentHostedProfileState.SUSPENDED));
    private static final Set<HostedEdge> ADVANCING_EDGES = Set.of(
            new HostedEdge(AgentHostedProfileState.PREPARED, AgentHostedProfileState.STAGED_DISABLED),
            new HostedEdge(AgentHostedProfileState.STAGED_DISABLED, AgentHostedProfileState.FILE_ENABLED),
            new HostedEdge(AgentHostedProfileState.SUSPENDING, AgentHostedProfileState.SUSPENDED));

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
            String nextState, long nextGeneration) {
        HostedEdge edge = new HostedEdge(expectedState, nextState);
        if (!ALLOWED_EDGES.contains(edge)) {
            fail();
        }
        if (ADVANCING_EDGES.contains(edge)) {
            requireSuccessor(expectedGeneration, nextGeneration);
        } else {
            requireNonNegative(expectedGeneration);
            if (nextGeneration != expectedGeneration) {
                fail();
            }
        }
    }

    static boolean requiresSuccessor(String checkpoint) {
        return AgentHostedProfileState.PREPARED.equals(checkpoint)
                || AgentHostedProfileState.STAGED_DISABLED.equals(checkpoint)
                || AgentHostedProfileState.SUSPENDING.equals(checkpoint);
    }

    private record HostedEdge(String expectedState, String nextState) {
    }

    private static void fail() {
        throw new AgentBizException(AgentErrorConstants.AGENT_ERROR,
                "Hosted profile lifecycle transition or generation is invalid or exhausted");
    }
}
