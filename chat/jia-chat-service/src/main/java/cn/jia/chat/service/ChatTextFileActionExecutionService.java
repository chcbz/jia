package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Zero-input text files enter the existing fenced CONVERSATION execution, never CHAT tools. */
@Service
public class ChatTextFileActionExecutionService {
    private final ChatDeliberationService deliberation;
    private final ChatDeliberationDao requests;
    private final ChatTypedDeliberationStore typed;
    private final ChatInteractionStepStore steps;
    private final AgentTaskExecutionGrantService grants;
    private final PersonalWorkspaceExecutionService executions;

    public ChatTextFileActionExecutionService(ChatDeliberationService deliberation, ChatDeliberationDao requests,
            ChatTypedDeliberationStore typed, ChatInteractionStepStore steps,
            AgentTaskExecutionGrantService grants, PersonalWorkspaceExecutionService executions) {
        this.deliberation=deliberation; this.requests=requests; this.typed=typed;
        this.steps=steps; this.grants=grants; this.executions=executions;
    }

    /** The old text-file admission is retired; v5 EXECUTE_NATIVE owns new actions. */
    public String admit(ChatActionFinalService.BoundAction action) { throw conflict(); }
    static String requestId(String event) { return stable("text-file-request",event); }
    private static String stable(String domain,String event) { return ChatBountyInteractionV3Wire.shaText(domain+"\n"+event); }
    private static ChatDeliberationException conflict() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Text file action changed");
    }
}
