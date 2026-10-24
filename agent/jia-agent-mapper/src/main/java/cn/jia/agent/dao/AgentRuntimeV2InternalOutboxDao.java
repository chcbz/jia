package cn.jia.agent.dao;

import cn.jia.agent.entity.*;
import java.util.*;

/** Typed stable-ID reads. No delete, retarget or original-byte updates. */
public interface AgentRuntimeV2InternalOutboxDao {
    Map<String,Object> delivery(long id);
    Map<String,Object> source(long id);
    List<Map<String,Object>> sources(long deliveryId);
    Map<String,Object> inbox(long id);
    Map<String,Object> inboxHint(long id);
    AgentRuntimeV2InternalOutboxRow hint(String id);
    AgentRuntimeV2InternalOutboxRow lock(String id);
    AgentRuntimeV2InternalOutboxRow forInbox(long inboxId);
    AgentRuntimeV2InternalOutboxRow forInboxHint(long inboxId);
    List<String> due(String owner,long now,int limit);
    List<String> expired(long now,int limit);
    List<String> stale(long now,int limit);
    void insert(AgentRuntimeV2InternalOutboxRow row);
    boolean transition(AgentRuntimeV2InternalOutboxRow before,Map<String,Object> mutable);
    void handoff(AgentInboxClaimToken token,AgentRuntimeV2InternalOutboxRow row,long now);
}
