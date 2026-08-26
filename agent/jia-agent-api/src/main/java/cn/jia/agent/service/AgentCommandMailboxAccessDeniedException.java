package cn.jia.agent.service;

/** Explicit no-leak mailbox authorization result for forbidden or missing scope. */
public final class AgentCommandMailboxAccessDeniedException extends RuntimeException {
    public AgentCommandMailboxAccessDeniedException() {
        super("mailbox scope is forbidden or missing");
    }
}
