package cn.jia.chat.service;

import cn.jia.agent.service.AgentSelectedOutputFinalizationService;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Trusted source boundary only. A completed ANSWER is not automatically a deliverable:
 * it must carry the persisted explicit delivery marker and callers must supply displayed
 * references; this service never lists/selects replies or infers historical delivery intent.
 * Ordinary v3 snapshot validation is reused. CLARIFY/ACTION_REQUEST/streaming prose are rejected. */
@Service
public class ChatCompletedMessageSourceService {
    private final ChatDeliberationService deliberations;
    private final ChatTypedDeliberationStore outcomes;
    private final ChatActionFinalService finals;
    private final ChatCompletedMessageSourceStore messages;
    public ChatCompletedMessageSourceService(ChatDeliberationService deliberations,
            ChatTypedDeliberationStore outcomes,
            ChatActionFinalService finals, ChatCompletedMessageSourceStore messages) {
        this.deliberations=Objects.requireNonNull(deliberations); this.outcomes=Objects.requireNonNull(outcomes); this.finals=Objects.requireNonNull(finals);
        this.messages=Objects.requireNonNull(messages);
    }
    public record Command(String taskId, String conversationId, long expectedAssignmentRevision,
            String requestId, SelectedOutputFinalizationDigest.MessageSource messageSource,
            String sha256, String title, String purpose) { }
    public record Resolved(long generation, String targetAgentId, long assignmentRevision,
            AgentSelectedOutputFinalizationService.SourceOutput output) { }

    @Transactional(readOnly=true)
    public Resolved resolve(ChatSelectedOutputFinalizationService.Scope owner, Command command) {
        validate(owner,command);
        try {
            var request=deliberations.getRequest(owner.tenantId(),owner.ownerJiacn(),owner.clientId(),command.requestId());
            if(request==null || !command.requestId().equals(request.requestId())
                    || !command.conversationId().equals(request.conversationId()) || !"COMPLETED".equals(request.state())
                    || request.turns()==null || request.turns().size()!=1) throw unavailable();
            long generation=positive(request.conversationGeneration()), revision=positive(request.requestRevision());
            var source=command.messageSource();
            var turn=request.turns().stream().filter(v->v!=null && source.turnId().equals(v.turnId())).findFirst().orElseThrow(ChatCompletedMessageSourceService::unavailable);
            if(!command.requestId().equals(turn.requestId()) || !request.requestRevision().equals(turn.requestRevision())
                    || !command.conversationId().equals(turn.conversationId()) || !request.conversationGeneration().equals(turn.conversationGeneration())
                    || !"CHAT".equals(turn.route())
                    || !("FINAL_PERSISTED".equals(turn.state()) || "PUBLISHED".equals(turn.state()))
                    || !source.messageId().equals(turn.finalMessageId()) || !source.snapshotId().equals(turn.contextSnapshotId())) throw unavailable();
            id(turn.targetAgentId(),100); id(turn.dispatchId(),100);
            // Natural discussion has a persisted typed admission/turn, not an execution step.
            // Current assignment/grant authorization is rechecked by existing promotion when wired.
            var scope=new ChatTypedDeliberationStore.Scope(owner.tenantId(),owner.ownerJiacn(),owner.clientId(),command.conversationId(),generation);
            // Reuses the normal reader that rebuilds the final digest from actual snapshot binding,
            // outcome union, admission and source catalogue. No copied hash is ownership authority.
            var projection=finals.readIfV3(scope,command.requestId(),source.turnId(),revision,"CHAT");
            var outcome=outcomes.findOutcomeByTurn(scope,source.turnId(),false);
            if(projection==null || !"READY".equals(projection.get("state")) || !(projection.get("outcome") instanceof Map<?,?> view)
                    || !"ANSWER".equals(view.get("kind")) || !Boolean.TRUE.equals(view.get("deliverable")) || outcome==null || !scope.equals(outcome.scope())
                    || !command.requestId().equals(outcome.requestId()) || revision!=outcome.requestRevision()
                    || !command.taskId().equals(outcome.taskId()) || command.expectedAssignmentRevision()!=outcome.assignmentRevision()
                    || !source.turnId().equals(outcome.turnId()) || positive(source.messageId())!=outcome.assistantMessageId()
                    || !source.finalDigest().equals(outcome.finalDigest()) || !"ANSWER".equals(outcome.kind())
                    || !source.finalDigest().equals(view.get("finalDigest")) || !Objects.equals(outcome.text(),view.get("text"))) throw unavailable();
            var message=messages.find(owner.tenantId(),owner.ownerJiacn(),owner.clientId(),command.taskId(),command.conversationId(),generation,positive(source.messageId()));
            if(message==null || !"ASSISTANT".equals(message.messageType()) || !"agent".equals(message.senderType())
                    || message.content()==null || message.content().isBlank() || !message.content().equals(outcome.text())) throw unavailable();
            var metadata=metadata(message.metadata());
            if(!command.requestId().equals(metadata.get("requestId")) || !source.turnId().equals(metadata.get("turnId"))
                    || !source.snapshotId().equals(metadata.get("contextSnapshotId")) || !source.finalDigest().equals(metadata.get("finalDigest"))
                    || !turn.dispatchId().equals(metadata.get("dispatchId")) || !turn.targetAgentId().equals(metadata.get("targetAgentId"))
                    || !turn.targetAgentId().equals(metadata.get("agentId")) || !"CHAT".equals(metadata.get("route"))
                    || !outcome.outcomeId().equals(metadata.get("outcomeId"))) throw unavailable();
            byte[] bytes=message.content().getBytes(StandardCharsets.UTF_8);
            if(!command.sha256().equals(sha256(bytes))) throw unavailable();
            return new Resolved(generation,turn.targetAgentId(),outcome.assignmentRevision(),
                    AgentSelectedOutputFinalizationService.SourceOutput.completedMessage(command.requestId(),null,
                            source,command.sha256(),command.title(),command.purpose(),bytes));
        } catch(ChatDeliberationException failure) {
            if(failure.reason()==ChatDeliberationException.Reason.PERSISTENCE_ERROR) throw failure;
            throw unavailable();
        }
    }
    private static void validate(ChatSelectedOutputFinalizationService.Scope owner, Command c) {
        if(owner==null || !"0".equals(owner.tenantId())) throw unavailable();
        id(owner.ownerJiacn(),50); id(owner.clientId(),50);
        if("0".equals(owner.ownerJiacn()) || c==null || c.messageSource()==null || c.expectedAssignmentRevision()<0
                || c.expectedAssignmentRevision()>9_007_199_254_740_991L) throw unavailable();
        id(c.taskId(),100); id(c.conversationId(),100); id(c.requestId(),100);
        id(c.messageSource().turnId(),100); id(c.messageSource().snapshotId(),100); positive(c.messageSource().messageId());
        if(c.sha256()==null || !c.sha256().matches("[0-9a-f]{64}") || c.messageSource().finalDigest()==null
                || !c.messageSource().finalDigest().matches("sha256:[0-9a-f]{64}")) throw unavailable();
        text(c.title()); text(c.purpose());
    }
    private static void id(String value,int max) { if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}")) throw unavailable(); }
    private static void text(String value) { if(value==null || value.isBlank() || value.length()>255 || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) throw unavailable(); }
    private static long positive(String value) { long result=decimal(value); if(result<1) throw unavailable(); return result; }
    private static long decimal(String value) { try { if(value==null || !value.matches("0|[1-9][0-9]*")) throw unavailable(); return Long.parseLong(value); } catch(NumberFormatException invalid) { throw unavailable(); } }
    @SuppressWarnings("unchecked") private static Map<String,Object> metadata(String value) {
        try { Map<String,Object> result=JsonUtil.getMapper().readValue(value,Map.class); if(result==null) throw unavailable(); return result; } catch(RuntimeException invalid) { throw unavailable(); }
    }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static ChatDeliberationException unavailable() { return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Completed message source is unavailable"); }
}
