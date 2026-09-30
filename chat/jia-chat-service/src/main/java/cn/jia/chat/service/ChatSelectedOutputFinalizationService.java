package cn.jia.chat.service;

import cn.jia.agent.service.AgentSelectedOutputFinalizationException;
import cn.jia.agent.service.AgentSelectedOutputFinalizationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix="chat.selected-output-finalization",name="enabled",havingValue="true")
public final class ChatSelectedOutputFinalizationService {
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private static final List<String> STAGES=List.of(
            "PROMOTING","READY_TO_SUBMIT","SUBMITTED","ACCEPTING","TASK_COMPLETED");
    private final ChatSelectedOutputFinalizationStore store;
    private final ChatDeliberationService deliberations;
    private final ChatInteractionStepStore steps;
    private final PersonalWorkspaceExecutionService executions;
    private final AgentSelectedOutputFinalizationService agent;

    public ChatSelectedOutputFinalizationService(ChatSelectedOutputFinalizationStore store,
            ChatDeliberationService deliberations,ChatInteractionStepStore steps,
            PersonalWorkspaceExecutionService executions,AgentSelectedOutputFinalizationService agent){
        this.store=Objects.requireNonNull(store);this.deliberations=Objects.requireNonNull(deliberations);
        this.steps=Objects.requireNonNull(steps);this.executions=Objects.requireNonNull(executions);
        this.agent=Objects.requireNonNull(agent);
    }

    public record Scope(String tenantId,String clientId,String ownerJiacn) { }
    public record Selection(String requestId,String stepId,String outputId,String sha256,String title,String purpose) { }
    public record Command(long expectedTaskVersion,long expectedAssignmentRevision,String conversationId,
            String summary,List<Selection> selectedOutputs) {
        public Command { selectedOutputs=selectedOutputs==null?null:List.copyOf(selectedOutputs); }
    }
    public record Receipt(String operationId,String taskId,String conversationId,String state,String stateVersion,
            String stage,String expectedTaskVersion,String expectedAssignmentRevision,List<Selection> selectedOutputs,
            String deliveryId,String deliveryState,String taskState,String taskVersion,String errorCode,boolean retryable) { }

    public Receipt submit(Scope scope,String taskId,String key,Command command){
        Valid valid=validate(scope,taskId,key,command);
        String operationId="fin_"+digest("operation\n"+wire(scope)+"\n"+taskId+"\n"+key);
        String immutable=SelectedOutputFinalizationDigest.request(taskId,command.expectedTaskVersion(),
                command.expectedAssignmentRevision(),command.conversationId(),command.summary(),
                command.selectedOutputs().stream().map(s->new SelectedOutputFinalizationDigest.Selection(
                        s.requestId(),s.stepId(),s.outputId(),s.sha256(),s.title(),s.purpose())).toList());
        List<ChatSelectedOutputFinalizationStore.Selection> rows=command.selectedOutputs().stream()
                .map(s->new ChatSelectedOutputFinalizationStore.Selection(s.requestId(),s.stepId(),s.outputId(),
                        s.sha256(),s.title(),s.purpose())).toList();
        ChatSelectedOutputFinalizationStore.Operation operation=store.create(scope.tenantId(),scope.ownerJiacn(),
                scope.clientId(),operationId,taskId,key,immutable,command.conversationId(),
                command.expectedTaskVersion(),command.expectedAssignmentRevision(),command.summary(),rows);
        if("completed".equals(operation.state())||("failed".equals(operation.state())&&!operation.retryable()))
            return receipt(operation);
        try {
            // A durable Agent commit wins before any now-stale source/version validation.
            try {
                operation=apply(scope,operation,agent.reconcile(agentScope(scope),taskId,operationId,immutable));
            } catch(AgentSelectedOutputFinalizationException absent) {
                if(absent.reason()!=AgentSelectedOutputFinalizationException.Reason.NOT_FOUND)throw absent;
                if(!"PROMOTING".equals(operation.stage()))throw absent;
            }
            if("completed".equals(operation.state()))return receipt(operation);
            if("changes_requested".equals(operation.deliveryState()))return changesRequested(scope,operation);
            if("PROMOTING".equals(operation.stage())) {
                Resolved resolved=resolve(scope,valid);
                var prepared=agent.prepare(agentScope(scope),new AgentSelectedOutputFinalizationService.PrepareCommand(
                        operationId,taskId,command.expectedTaskVersion(),command.expectedAssignmentRevision(),
                        command.conversationId(),resolved.generation(),resolved.targetAgentId(),resolved.grantId(),
                        resolved.grantVersion(),command.summary(),immutable,resolved.outputs()));
                operation=apply(scope,operation,prepared);
            }
            if("READY_TO_SUBMIT".equals(operation.stage()))
                operation=apply(scope,operation,agent.submit(agentScope(scope),taskId,operationId,immutable));
            if("changes_requested".equals(operation.deliveryState()))return changesRequested(scope,operation);
            if("SUBMITTED".equals(operation.stage()))
                operation=store.advance(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),operation,
                        "pending","ACCEPTING",operation.deliveryId(),operation.deliveryState(),operation.taskState(),
                        operation.taskVersion(),null,false);
            if("ACCEPTING".equals(operation.stage()))
                operation=apply(scope,operation,agent.accept(agentScope(scope),taskId,operationId,immutable));
            if("changes_requested".equals(operation.deliveryState()))return changesRequested(scope,operation);
            return receipt(operation);
        } catch(RuntimeException failure) {
            if(failure instanceof ChatSelectedOutputFinalizationStore.Conflict)throw failure;
            Failure safe=classify(failure);
            var task=store.task(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),taskId);
            operation=store.advance(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),operation,
                    "failed",operation.stage(),operation.deliveryId(),operation.deliveryState(),task.state(),task.version(),
                    safe.code(),safe.retryable());
            return receipt(operation);
        }
    }

    public Receipt getByOperation(Scope scope,String taskId,String operationId){
        validateScope(scope);id(taskId,100);id(operationId,100);
        var row=store.findByOperation(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),taskId,operationId);
        if(row==null)throw new ChatSelectedOutputFinalizationStore.Missing();return receipt(row);
    }
    public Receipt getByKey(Scope scope,String taskId,String key){
        validateScope(scope);id(taskId,100);key(key);
        var row=store.findByKey(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),taskId,key);
        if(row==null)throw new ChatSelectedOutputFinalizationStore.Missing();return receipt(row);
    }

    private Resolved resolve(Scope scope,Valid valid){
        PersonalWorkspaceExecutionService.OwnerScope owner=new PersonalWorkspaceExecutionService.OwnerScope(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        List<AgentSelectedOutputFinalizationService.SourceOutput> result=new ArrayList<>();
        String target=null,grant=null;long grantVersion=-1,generation=-1;
        for(Selection selected:valid.command().selectedOutputs()){
            ChatDeliberationService.RequestView request;
            try {
                request=deliberations.getRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),selected.requestId());
            } catch(ChatDeliberationException unavailable) {
                if(unavailable.reason()==ChatDeliberationException.Reason.PERSISTENCE_ERROR)throw unavailable;
                throw sourceUnavailable();
            }
            if(request==null || !selected.requestId().equals(request.requestId())
                    || !valid.command().conversationId().equals(request.conversationId())
                    || !"OUTPUT_COMMITTED".equals(request.state()) || request.steps()==null)
                throw sourceUnavailable();
            long requestGeneration=decimal(request.conversationGeneration());
            long revision=positiveDecimal(request.requestRevision());
            ChatDeliberationService.StepView stepView=request.steps().stream()
                    .filter(v->v!=null && selected.stepId().equals(v.stepId())).findFirst()
                    .orElseThrow(ChatSelectedOutputFinalizationService::sourceUnavailable);
            if(!valid.taskId().equals(stepView.taskId()) || !"EXECUTE".equals(stepView.kind())
                    || !"OUTPUT_COMMITTED".equals(stepView.state())
                    || valid.command().expectedAssignmentRevision()!=decimal(stepView.assignmentRevision())
                    || stepView.executionId()==null || stepView.executionIntentId()==null
                    || stepView.targetAgentId()==null) throw sourceUnavailable();
            long stepNumber=positiveDecimal(stepView.stepNumber());
            List<ChatInteractionStepStore.Step> persistedRows=steps.findSteps(scope.tenantId(),
                    scope.ownerJiacn(),scope.clientId(),selected.requestId(),revision);
            if(persistedRows==null)throw sourceUnavailable();
            ChatInteractionStepStore.Step persisted=persistedRows.stream()
                    .filter(v->v!=null && selected.stepId().equals(v.stepId())).findFirst()
                    .orElseThrow(ChatSelectedOutputFinalizationService::sourceUnavailable);
            ChatInteractionStepStore.ExecutionLink link=steps.findLink(scope.tenantId(),scope.ownerJiacn(),
                    scope.clientId(),selected.stepId());
            if(!scopeExact(scope,persisted.tenantId(),persisted.ownerJiacn(),persisted.clientId())
                    || !selected.requestId().equals(persisted.requestId()) || persisted.requestRevision()!=revision
                    || persisted.stepNumber()!=stepNumber || !valid.command().conversationId().equals(persisted.conversationId())
                    || persisted.conversationGeneration()!=requestGeneration || !valid.taskId().equals(persisted.taskId())
                    || persisted.assignmentRevision()!=valid.command().expectedAssignmentRevision()
                    || !stepView.targetAgentId().equals(persisted.targetAgentId())
                    || !"EXECUTE".equals(persisted.kind()) || !"OUTPUT_COMMITTED".equals(persisted.state())
                    || persisted.grantId()==null || persisted.grantVersion()<1
                    || link==null || !scopeExact(scope,link.tenantId(),link.ownerJiacn(),link.clientId())
                    || !selected.stepId().equals(link.stepId())
                    || !stepView.executionIntentId().equals(link.executionIntentId())
                    || !stepView.executionId().equals(link.executionId()) || !"RUNNING".equals(link.state()))
                throw sourceUnavailable();
            var source=store.source(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),valid.command().conversationId(),
                    requestGeneration,selected.requestId(),selected.stepId(),selected.outputId());
            if(source==null || source.generation()!=requestGeneration
                    || !stepView.executionId().equals(source.executionId())
                    || !selected.sha256().equals(source.sha256()))throw sourceUnavailable();
            PersonalWorkspaceExecutionService.ConversationOutput output;
            try {
                output=executions.readConversationOutput(owner,valid.taskId(),source.runId(),selected.outputId());
            } catch(PersonalWorkspaceExecutionService.Failure unavailable) {
                if(unavailable.getReason()==PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE)throw unavailable;
                throw sourceUnavailable();
            }
            if(output==null || !source.executionId().equals(output.executionId())
                    || !selected.outputId().equals(output.outputId())
                    || !source.mime().equals(output.contentMimeType()) || !source.sha256().equals(output.sha256())
                    || source.byteLength()!=output.byteLength() || output.bytes()==null
                    || output.bytes().length!=output.byteLength() || !output.sha256().equals(digest(output.bytes())))
                throw sourceUnavailable();
            if(target==null){target=persisted.targetAgentId();grant=persisted.grantId();
                grantVersion=persisted.grantVersion();generation=requestGeneration;}
            else if(!target.equals(persisted.targetAgentId())||!grant.equals(persisted.grantId())
                    ||grantVersion!=persisted.grantVersion()||generation!=requestGeneration)throw sourceUnavailable();
            result.add(new AgentSelectedOutputFinalizationService.SourceOutput(selected.requestId(),selected.stepId(),
                    source.executionId(),source.runId(),selected.outputId(),source.sha256(),source.mime(),source.byteLength(),
                    selected.title(),selected.purpose(),output.bytes()));
        }
        if(target==null||grant==null||grantVersion<1||generation<1)throw sourceUnavailable();
        return new Resolved(generation,target,grant,grantVersion,List.copyOf(result));
    }

    private ChatSelectedOutputFinalizationStore.Operation apply(Scope scope,
            ChatSelectedOutputFinalizationStore.Operation current,AgentSelectedOutputFinalizationService.PromotionView view){
        validateView(current,view);
        int currentRank=stageRank(current.stage()),incomingRank=stageRank(view.stage());
        if(incomingRank<currentRank) {
            if("SUBMITTED".equals(view.stage())&&"changes_requested".equals(view.deliveryState())
                    &&currentRank<=stageRank("ACCEPTING")) {
                requireNonRegressingProjection(current,view);
                return store.advance(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),current,"pending",current.stage(),
                        view.deliveryId(),view.deliveryState(),view.taskState(),view.taskVersion(),null,false);
            }
            return current;
        }
        String state="TASK_COMPLETED".equals(view.stage())?"completed":"pending";
        if(incomingRank==currentRank) {
            requireNonRegressingProjection(current,view);
            if(sameProjection(current,state,view))return current;
        }
        return store.advance(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),current,state,view.stage(),
                view.deliveryId(),view.deliveryState(),view.taskState(),view.taskVersion(),null,false);
    }
    private static void validateView(ChatSelectedOutputFinalizationStore.Operation current,
            AgentSelectedOutputFinalizationService.PromotionView view) {
        if(view==null || !current.operationId().equals(view.operationId())
                || !current.taskId().equals(view.taskId()) || stageRank(view.stage())<0
                || view.taskState()==null || view.taskState().isBlank()
                || view.taskVersion()<0 || view.taskVersion()>MAX_SAFE_INTEGER)throw projectionInvalid();
        if("TASK_COMPLETED".equals(view.stage())) {
            if(view.deliveryId()==null || !"accepted".equals(view.deliveryState())
                    || !"completed".equals(view.taskState()))throw projectionInvalid();
            return;
        }
        if("SUBMITTED".equals(view.stage())) {
            if(view.deliveryId()==null || !("submitted".equals(view.deliveryState())
                    || "changes_requested".equals(view.deliveryState())))
                throw projectionInvalid();
            return;
        }
        if("ACCEPTING".equals(view.stage())) {
            if(view.deliveryId()==null || !"submitted".equals(view.deliveryState()))throw projectionInvalid();
            return;
        }
        if("PROMOTING".equals(view.stage()) || "READY_TO_SUBMIT".equals(view.stage())) {
            if(view.deliveryId()!=null || view.deliveryState()!=null)throw projectionInvalid();
        }
    }
    private static void requireNonRegressingProjection(ChatSelectedOutputFinalizationStore.Operation current,
            AgentSelectedOutputFinalizationService.PromotionView incoming) {
        if(incoming.taskVersion()<current.taskVersion()
                || (current.deliveryId()!=null && !current.deliveryId().equals(incoming.deliveryId()))
                || ("changes_requested".equals(current.deliveryState())
                    && !"changes_requested".equals(incoming.deliveryState()))
                || ("completed".equals(current.taskState()) && !"completed".equals(incoming.taskState())))
            throw projectionInvalid();
    }
    private Receipt changesRequested(Scope scope,ChatSelectedOutputFinalizationStore.Operation operation) {
        if(!("failed".equals(operation.state())&&!operation.retryable()
                &&"FINALIZATION_DELIVERY_CHANGES_REQUESTED".equals(operation.errorCode())))
            operation=store.advance(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),operation,
                    "failed",operation.stage(),operation.deliveryId(),operation.deliveryState(),operation.taskState(),
                    operation.taskVersion(),"FINALIZATION_DELIVERY_CHANGES_REQUESTED",false);
        return receipt(operation);
    }
    private static boolean sameProjection(ChatSelectedOutputFinalizationStore.Operation current,String state,
            AgentSelectedOutputFinalizationService.PromotionView view) {
        return state.equals(current.state()) && view.stage().equals(current.stage())
                && Objects.equals(view.deliveryId(),current.deliveryId())
                && Objects.equals(view.deliveryState(),current.deliveryState())
                && Objects.equals(view.taskState(),current.taskState())
                && view.taskVersion()==current.taskVersion() && current.errorCode()==null && !current.retryable();
    }
    private static int stageRank(String stage){return STAGES.indexOf(stage);}
    private static Receipt receipt(ChatSelectedOutputFinalizationStore.Operation row){
        requirePersistedReceipt(row);
        return new Receipt(row.operationId(),row.taskId(),row.conversationId(),row.state(),Long.toString(row.stateVersion()),
                row.stage(),Long.toString(row.expectedTaskVersion()),Long.toString(row.expectedAssignmentRevision()),
                row.selections().stream().map(s->new Selection(s.requestId(),s.stepId(),s.outputId(),s.sha256(),s.title(),s.purpose())).toList(),
                row.deliveryId(),row.deliveryState(),row.taskState(),Long.toString(row.taskVersion()),row.errorCode(),row.retryable());
    }
    private static void requirePersistedReceipt(ChatSelectedOutputFinalizationStore.Operation row) {
        if(row==null)throw persistence();
        persistedId(row.operationId(),100);persistedId(row.taskId(),100);persistedId(row.conversationId(),100);
        if(row.digest()==null||!row.digest().matches("[0-9a-f]{64}")||row.stateVersion()<1
                || row.expectedTaskVersion()<0||row.expectedTaskVersion()>MAX_SAFE_INTEGER
                || row.expectedAssignmentRevision()<0||row.expectedAssignmentRevision()>MAX_SAFE_INTEGER
                || row.taskVersion()<0||row.taskVersion()>MAX_SAFE_INTEGER
                || !Set.of("pending","completed","failed").contains(row.state())||stageRank(row.stage())<0
                || row.taskState()==null||row.taskState().isBlank()
                || row.selections()==null||row.selections().isEmpty()||row.selections().size()>99)
            throw persistence();
        Set<String> sources=new LinkedHashSet<>();
        for(var selected:row.selections()) {
            if(selected==null)throw persistence();
            persistedId(selected.requestId(),100);persistedId(selected.stepId(),100);persistedId(selected.outputId(),100);
            if(selected.sha256()==null||!selected.sha256().matches("[0-9a-f]{64}"))throw persistence();
            persistedText(selected.title(),255);persistedText(selected.purpose(),255);
            if(!sources.add(selected.requestId()+"\0"+selected.stepId()+"\0"+selected.outputId()))throw persistence();
        }
        boolean terminal="completed".equals(row.state());
        if(terminal!=("TASK_COMPLETED".equals(row.stage())&&row.deliveryId()!=null
                &&"accepted".equals(row.deliveryState())&&"completed".equals(row.taskState())
                &&row.errorCode()==null&&!row.retryable()))throw persistence();
        if("pending".equals(row.state())&&(row.errorCode()!=null||row.retryable()))throw persistence();
        if("failed".equals(row.state())&&(row.errorCode()==null||row.errorCode().isBlank()))throw persistence();
        if(List.of("PROMOTING","READY_TO_SUBMIT").contains(row.stage())
                &&(row.deliveryId()!=null||row.deliveryState()!=null))throw persistence();
        if(List.of("SUBMITTED","ACCEPTING").contains(row.stage())) {
            if(row.deliveryId()==null||!("submitted".equals(row.deliveryState())
                    || "changes_requested".equals(row.deliveryState())))throw persistence();
        }
        if(row.deliveryId()!=null)persistedId(row.deliveryId(),100);
    }

    private static Valid validate(Scope scope,String taskId,String key,Command c){
        validateScope(scope);id(taskId,100);key(key);if(c==null)throw bad();
        if(c.expectedTaskVersion()<0||c.expectedTaskVersion()>MAX_SAFE_INTEGER
                ||c.expectedAssignmentRevision()<0||c.expectedAssignmentRevision()>MAX_SAFE_INTEGER)throw bad();
        id(c.conversationId(),100);text(c.summary(),4000);
        if(c.selectedOutputs()==null||c.selectedOutputs().isEmpty()||c.selectedOutputs().size()>99)throw bad();
        Set<String> sources=new LinkedHashSet<>();
        for(Selection s:c.selectedOutputs()){if(s==null)throw bad();id(s.requestId(),100);id(s.stepId(),100);id(s.outputId(),100);
            if(s.sha256()==null||!s.sha256().matches("[0-9a-f]{64}"))throw bad();text(s.title(),255);text(s.purpose(),255);
            if(!sources.add(s.requestId()+"\0"+s.stepId()+"\0"+s.outputId()))throw bad();}
        return new Valid(taskId,c);
    }
    private static boolean scopeExact(Scope scope,String tenant,String owner,String client){
        return scope.tenantId().equals(tenant)&&scope.ownerJiacn().equals(owner)&&scope.clientId().equals(client);
    }
    private static String wire(Scope s){return s.tenantId()+"\0"+s.ownerJiacn()+"\0"+s.clientId();}
    private static AgentSelectedOutputFinalizationService.Scope agentScope(Scope s){
        return new AgentSelectedOutputFinalizationService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
    }
    private static Failure classify(RuntimeException e){
        if(e instanceof AgentSelectedOutputFinalizationException a)
            return new Failure(a.reason().code(),a.reason().retryable());
        if(e instanceof SourceUnavailable)return new Failure("FINALIZATION_SOURCE_UNAVAILABLE",false);
        if(e instanceof PersonalWorkspaceExecutionService.Failure f)
            return f.getReason()==PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE
                    ?new Failure("FINALIZATION_STORAGE_UNAVAILABLE",true)
                    :new Failure("FINALIZATION_SOURCE_UNAVAILABLE",false);
        if(e instanceof ChatDeliberationException deliberation)
            return deliberation.reason()==ChatDeliberationException.Reason.PERSISTENCE_ERROR
                    ?new Failure("FINALIZATION_INTERNAL_ERROR",true)
                    :new Failure("FINALIZATION_SOURCE_UNAVAILABLE",false);
        if(e instanceof ProjectionInvalid)return new Failure("FINALIZATION_INTERNAL_ERROR",true);
        return new Failure("FINALIZATION_INTERNAL_ERROR",true);
    }
    private static long decimal(String v){
        try{long x=Long.parseLong(v);if(x<0||x>MAX_SAFE_INTEGER)throw new NumberFormatException();return x;}
        catch(Exception e){throw sourceUnavailable();}
    }
    private static long positiveDecimal(String v){long value=decimal(v);if(value<1)throw sourceUnavailable();return value;}
    private static String digest(String v){return digest(v.getBytes(StandardCharsets.UTF_8));}
    private static String digest(byte[] v){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void validateScope(Scope s){
        if(s==null||!"0".equals(s.tenantId())||!safeId(s.clientId(),50)||!safeId(s.ownerJiacn(),50)
                ||"0".equals(s.ownerJiacn()))throw new ChatSelectedOutputFinalizationStore.Missing();
    }
    private static boolean safeId(String value,int max){
        return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}");
    }
    private static void id(String v,int max){
        if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}"))throw bad();
    }
    private static void key(String v){
        if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}"))throw bad();
    }
    private static void text(String v,int max){
        if(v==null||v.isBlank()||v.length()>max||!v.equals(v.strip())||v.indexOf('\0')>=0
                ||v.codePoints().anyMatch(Character::isISOControl))throw bad();
    }
    private static void persistedId(String value,int max){try{id(value,max);}catch(IllegalArgumentException invalid){throw persistence();}}
    private static void persistedText(String value,int max){try{text(value,max);}catch(IllegalArgumentException invalid){throw persistence();}}
    private static IllegalArgumentException bad(){return new IllegalArgumentException("Invalid finalization request");}
    private static ChatSelectedOutputFinalizationStore.Persistence persistence(){return new ChatSelectedOutputFinalizationStore.Persistence();}
    private static SourceUnavailable sourceUnavailable(){return new SourceUnavailable();}
    private static ProjectionInvalid projectionInvalid(){return new ProjectionInvalid();}
    private record Valid(String taskId,Command command) { }
    private record Resolved(long generation,String targetAgentId,String grantId,long grantVersion,
            List<AgentSelectedOutputFinalizationService.SourceOutput> outputs) { }
    private record Failure(String code,boolean retryable) { }
    private static final class SourceUnavailable extends RuntimeException { }
    private static final class ProjectionInvalid extends RuntimeException { }
}
