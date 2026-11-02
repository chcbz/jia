package cn.jia.agent.service;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import java.util.List;

/** Durable queue discovery for the current WS node; HTTP remains the execution authority. */
public interface NativeConversationWorkDiscovery {
    void afterCommit(NativeConversationWorkAvailable target);
    List<NativeConversationWorkAvailable> pendingFor(List<NativeConversationWorkAvailable> targets);
}
