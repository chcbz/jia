package cn.jia.chat.api;

import cn.jia.agent.service.*;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.service.*;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

/** Exact execution result read; never project, admit, invoke, or scan conversation history. */
@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix="chat.bounty-media",name="enabled",havingValue="true")
public final class ChatSkillResultController {
    private final JdbcTemplate jdbc;private final ChatConversationDao conversations;private final ChatSkillSourceAccessService sources;
    private final SkillExecutionService skills;private final ChatBountyMediaController media;private final HumanSenderIdentityResolver identities;private final TenantScopeResolver tenants;
    public ChatSkillResultController(JdbcTemplate jdbc,ChatConversationDao conversations,ChatSkillSourceAccessService sources,SkillExecutionService skills,
            ChatBountyMediaController media,HumanSenderIdentityResolver identities,TenantScopeResolver tenants) {
        this.jdbc=jdbc;this.conversations=conversations;this.sources=sources;this.skills=skills;this.media=media;this.identities=identities;this.tenants=tenants;
    }
    @GetMapping("/{conversationId}/executions/{executionId}/result")
    public ResponseEntity<JsonResult<Map<String,Object>>> result(@PathVariable String conversationId,@PathVariable String executionId,Authentication authentication) {
        if(!id(conversationId)||!id(executionId))throw missing();var sender=identities.resolve(EsContextHolder.getContext());String tenant=tenants.resolve(authentication);
        if(sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type()))throw missing();
        var owner=new PersonalWorkspaceExecutionService.OwnerScope(tenant,sender.clientId(),sender.jiacn());
        var conversation=conversations.findScopedById(sender.jiacn(),sender.clientId(),conversationId);
        if(conversation==null||conversation.getTaskId()==null||conversation.getLifecycleGeneration()==null)throw missing();
        sources.conversation(owner,conversation.getTaskId(),conversationId,conversation.getLifecycleGeneration());
        var rows=jdbc.queryForList("""
            SELECT s.request_id,s.step_id,s.state AS step_state,l.state AS link_state,r.aggregate_state AS request_state
            FROM chat_step_execution_link l JOIN chat_interaction_step s ON BINARY s.step_id=BINARY l.step_id
              AND BINARY s.tenant_id=BINARY l.tenant_id AND BINARY s.client_id=BINARY l.client_id AND BINARY s.owner_jiacn=BINARY l.owner_jiacn
            JOIN chat_request r ON BINARY r.tenant_id=BINARY s.tenant_id AND BINARY r.client_id=BINARY s.client_id AND BINARY r.owner_jiacn=BINARY s.owner_jiacn
              AND BINARY r.request_id=BINARY s.request_id AND r.request_revision=s.request_revision
            JOIN agent_personal_workspace_execution e ON BINARY e.execution_id=BINARY l.execution_id
              AND BINARY e.tenant_id=BINARY l.tenant_id AND BINARY e.client_id=BINARY l.client_id AND BINARY e.owner_jiacn=BINARY l.owner_jiacn
            WHERE BINARY l.tenant_id=BINARY ? AND BINARY l.client_id=BINARY ? AND BINARY l.owner_jiacn=BINARY ? AND BINARY l.execution_id=BINARY ?
              AND BINARY s.conversation_id=BINARY ? AND s.conversation_generation=? AND BINARY r.conversation_id=BINARY s.conversation_id
              AND r.conversation_generation=s.conversation_generation AND BINARY e.conversation_id=BINARY s.conversation_id
              AND BINARY e.task_id=BINARY s.task_id AND BINARY s.task_id=BINARY ? AND s.kind='EXECUTE' AND e.execution_protocol_version=4
            """,tenant,sender.clientId(),sender.jiacn(),executionId,conversationId,conversation.getLifecycleGeneration(),conversation.getTaskId());
        if(rows.size()!=1)throw missing();var chain=rows.getFirst();var view=skills.result(owner,executionId);if(view==null||!executionId.equals(view.executionId()))throw missing();
        boolean complete="COMPLETE".equals(view.phase());
        if(complete&&!("RESULT_COMMITTED".equals(chain.get("step_state"))&&"RESULT_COMMITTED".equals(chain.get("link_state"))&&"RESULT_COMMITTED".equals(chain.get("request_state"))))throw unavailable();
        var body=new LinkedHashMap<String,Object>();body.put("schemaVersion",1);body.put("conversationId",conversationId);body.put("conversationGeneration",Long.toString(conversation.getLifecycleGeneration()));
        body.put("taskId",conversation.getTaskId());body.put("requestId",chain.get("request_id"));body.put("stepId",chain.get("step_id"));body.put("executionId",executionId);
        body.put("state",view.state());body.put("phase",view.phase());body.put("resultDigest",view.resultManifest()==null?null:view.resultManifest().get("resultDigest"));body.put("data",view.resultManifest()==null?null:view.resultManifest().get("data"));
        Object outputs=List.of();if(view.resultManifest()!=null) {
            var manifest=view.resultManifest();var digest=new LinkedHashMap<>(manifest);digest.remove("manifestId");digest.remove("resultDigest");
            if(!ChatSkillSnapshotJson.sha(digest).equals(manifest.get("resultDigest")))throw unavailable();
            if (complete) {
                var response=media.outputs((String)chain.get("request_id"),(String)chain.get("step_id"),authentication);outputs=response.getBody().getData();
            }
        }
        body.put("outputs",outputs);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(JsonResult.success(Collections.unmodifiableMap(body)));
    }
    @ExceptionHandler({ChatDeliberationException.class,PersonalWorkspaceExecutionService.Failure.class})
    public ResponseEntity<JsonResult<Void>> failure(RuntimeException error) {
        int status=error instanceof ChatDeliberationException e&&e.reason()==ChatDeliberationException.Reason.PERSISTENCE_ERROR?503:
                error instanceof PersonalWorkspaceExecutionService.Failure f&&f.getReason()!=PersonalWorkspaceExecutionService.Reason.NOT_FOUND?503:404;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(JsonResult.failure("SKILL_RESULT_"+status,status==404?"Result is unavailable":"Result source unavailable"));
    }
    private static boolean id(String s){return s!=null&&s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");}
    private static ChatDeliberationException missing(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Result unavailable");}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Result projection incomplete");}
}
