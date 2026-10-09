package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRuntimeV2DispatchTransactionTest;
import org.junit.jupiter.api.Test;

/** Regression retained: real composite prepare locks controls; transport must never retain those locks. */
class AgentCommandInboxV2ExecutionAdmissionConcurrencyTest {
    @Test void compositePrepareSerializesWithControlTransition() throws Exception {AgentRuntimeV2DispatchTransactionTest.verifyPrepareSerialization();}
    @Test void transportHasNoTransactionSynchronizationResourcesOrControlLocks() throws Exception {AgentRuntimeV2DispatchTransactionTest.verifyPostTransactionTransport();}
    @Test void commitFailureHasZeroSocketAndOutbox() throws Exception {AgentRuntimeV2DispatchTransactionTest.verifyCommitFailureZeroSend();}
    @Test void controlCloseCommittedFirstHasZeroNewDispatch() throws Exception {AgentRuntimeV2DispatchTransactionTest.verifyControlCloseFirstZeroDispatch();}
}
