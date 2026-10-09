package cn.jia.chat.service;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.chat.handler.AgentWebSocketHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
@Service
public final class SkillManagedSessionAdapter implements AgentManagedSessionLookup {
    private final ObjectProvider<AgentWebSocketHandler> handler;
    public SkillManagedSessionAdapter(ObjectProvider<AgentWebSocketHandler> handler) { this.handler=handler; }
    @Override public boolean isReady(String tenant,String client,String agent,SessionFence fence) {
        var current=handler.getIfAvailable();return current!=null && current.isManagedSkillSessionReady(tenant,client,agent,fence);
    }
    @Override public cn.jia.agent.entity.AgentRawCommandDispatchResult dispatch(String t,String c,String a,SessionFence fence,byte[] wire) {
        var current=handler.getIfAvailable();return current==null?cn.jia.agent.entity.AgentRawCommandDispatchResult.offline():current.dispatchManagedSkill(t,c,a,fence,wire);
    }
}
