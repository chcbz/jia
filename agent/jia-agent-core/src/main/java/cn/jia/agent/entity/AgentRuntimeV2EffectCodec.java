package cn.jia.agent.entity;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;

/**
 * Additive R4 exact raw boundary. Typed resources do not authorize a path/tool
 * or change the frozen command/grant/renew/ACK codec. Historical data is never
 * an executable permit. Resource/result adapters remain separately required.
 */
public final class AgentRuntimeV2EffectCodec {
    public static final int FRAME_CAP=131072, CHUNK_CAP=65536, RESOURCE_CAP=8192;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private AgentRuntimeV2EffectCodec() {}
    public enum Kind { SKILL_PACKAGE_READ, SKILL_ACTIVATE, SKILL_RESULT, WORKSPACE_PREPARE, WORKSPACE_MUTATE, FILE_DESCRIBE, FILE_INPUT_READ, FILE_STAGE, FILE_COMMIT, PROCESS_START, PROCESS_EXIT }
    public enum Phase { DESCRIBE, READ, STAGE, ADMIT, OBSERVE, COMMIT, SETTLE }
    public enum Access { OPEN, SETTLE_ONLY, DENIED }
    public enum Subject { RENEWAL, EXECUTION, EFFECT, EFFECT_SUCCESS }
    public sealed interface Result permits Control,ReadResult,StagedResult,AdmittedResult,SettledResult,DescribedResult,CommittedResult,ObservedResult,Reconciliation {}
    private static final Set<String> SIGNALS=Set.of("SIGHUP","SIGINT","SIGQUIT","SIGILL","SIGTRAP","SIGABRT","SIGBUS","SIGFPE","SIGKILL","SIGUSR1","SIGSEGV","SIGUSR2","SIGPIPE","SIGALRM","SIGTERM","SIGCHLD","SIGCONT","SIGSTOP","SIGTSTP","SIGTTIN","SIGTTOU","SIGURG","SIGXCPU","SIGXFSZ","SIGVTALRM","SIGPROF","SIGWINCH","SIGIO","SIGSYS");
    private static final Map<Kind,Set<Phase>> PHASES=Map.ofEntries(
        Map.entry(Kind.SKILL_PACKAGE_READ,Set.of(Phase.READ)), Map.entry(Kind.SKILL_ACTIVATE,Set.of(Phase.ADMIT,Phase.OBSERVE)), Map.entry(Kind.SKILL_RESULT,Set.of(Phase.SETTLE)),
        Map.entry(Kind.WORKSPACE_PREPARE,Set.of(Phase.DESCRIBE,Phase.ADMIT,Phase.OBSERVE)), Map.entry(Kind.WORKSPACE_MUTATE,Set.of(Phase.ADMIT,Phase.OBSERVE)),
        Map.entry(Kind.FILE_DESCRIBE,Set.of(Phase.DESCRIBE)), Map.entry(Kind.FILE_INPUT_READ,Set.of(Phase.READ)), Map.entry(Kind.FILE_STAGE,Set.of(Phase.STAGE)), Map.entry(Kind.FILE_COMMIT,Set.of(Phase.COMMIT,Phase.OBSERVE)),
        Map.entry(Kind.PROCESS_START,Set.of(Phase.ADMIT,Phase.OBSERVE)), Map.entry(Kind.PROCESS_EXIT,Set.of(Phase.OBSERVE)));
    public sealed interface Resource permits SkillRead,SkillIdentity,WorkspacePrepare,WorkspaceMutate,FileDescribe,FileRead,FileStage,FileCommit,ProcessStart,ProcessExit {}
    public record SkillIdentity(String installationId,String productVersionId,String packageDigest,String skillKey,String skillVersion) implements Resource {}
    public record SkillRead(SkillIdentity identity,long offset,long length) implements Resource {}
    public record WorkspacePrepare(String taskId,String workItemId,String workspaceKey,String baseTreeSha256) implements Resource {}
    public record WorkspaceMutate(String taskId,String workItemId,String workspaceKey,String baseTreeSha256,String manifestSha256) implements Resource {}
    public record FileDescribe(String taskId,String workItemId) implements Resource {}
    public record FileRead(String executionId,String taskId,String workItemId,String runId,String inputId,long offset,long length) implements Resource {}
    public record FileStage(String executionId,String taskId,String workItemId,String runId,String outputId,String contentSha256,long totalLength,long offset,long length) implements Resource {}
    public record FileCommit(String executionId,String taskId,String workItemId,String runId,String manifestId,String manifestSha256) implements Resource {}
    public record ProcessStart(String taskId,String workItemId,String engine,String workspaceKey) implements Resource {}
    public record ProcessExit(String taskId,String workItemId,String engine,String workspaceKey,long pid,long startTime,Integer exitCode,String signal) implements Resource {}
    public record GrantReference(String requestId,String runtimeDispatchId,String startedReceiptId,String startedReceiptSha256,String executionGrantId,String executionGrantSha256) {}
    public sealed interface Request permits Inspect,EffectRequest,Reconcile {}
    public record Inspect(GrantReference grant) implements Request {}
    public record EffectRequest(GrantReference grant,String runToken,String effectId,Kind effectKind,Phase phase,Resource resource,byte[] content) implements Request {
        public EffectRequest { content=content==null?null:content.clone(); }
        @Override public byte[] content(){return content==null?null:content.clone();}
    }
    public record Reconcile(String requestId,String runtimeDispatchId,Subject subjectKind,String subjectId,byte[] originalRequest,byte[] evidence) implements Request {
        public Reconcile { originalRequest=originalRequest==null?null:originalRequest.clone(); evidence=evidence==null?null:evidence.clone(); }
        @Override public byte[] originalRequest(){return originalRequest==null?null:originalRequest.clone();}
        @Override public byte[] evidence(){return evidence==null?null:evidence.clone();}
    }
    public record Control(Access access,String runtimeBindingId,String runtimeInstanceId,long holderBindingGeneration,String holderGenerationFactId,String holderGenerationSnapshotSha256,
        String executionGrantId,String executionGrantSha256,String currentLeaseFactId,long leaseRevision,long leaseUntil,long tenantControlRevision,long moduleModeRevision,long deploymentRegistrationRevision,
        String tenantState,String moduleState,String deploymentState,String drainOperationId,Long cutoffAt,Long deadlineAt,long checkedAt,long validUntil) implements Result {}
    public record ReadResult(long offset,long length,long totalLength,String sha256,byte[] content) implements Result {
        public ReadResult{content=content==null?null:content.clone();}
        @Override public byte[] content(){return content==null?null:content.clone();}
    }
    public record StagedResult(String stagedId,String contentSha256,long length) implements Result {}
    public record AdmittedResult(String effectId,String resourceSha256,long admittedAt,long validUntil,String holderGenerationFactId,String currentLeaseFactId) implements Result {}
    public record SettledResult(String effectId,String resourceSha256,String disposition,String commitReceiptId,String settlementReceiptId,long settledAt) implements Result {}
    public sealed interface Descriptor permits CodexDescriptor,SkillDescriptor,FileDescriptor {}
    public record CodexDescriptor(String taskId,String workItemId,String engine,String workspaceKey,String baseTreeSha256) implements Descriptor {}
    public record SkillDescriptor(SkillIdentity identity,long packageSize) implements Descriptor {}
    public record FileInput(String inputId,String relativePath,String downloadPath,long length,String sha256) {}
    public record FileOutput(String outputId,String relativePath,String contentType,long maxLength,String uploadPath) {}
    public record FileDescriptor(String taskId,String runId,List<FileInput> inputManifest,List<FileOutput> outputManifest) implements Descriptor {
        public FileDescriptor{inputManifest=List.copyOf(inputManifest);outputManifest=List.copyOf(outputManifest);}
    }
    public record DescribedResult(String branch,String executionId,String workItemId,Descriptor descriptor) implements Result {}
    public record CommitItem(String outputId,String fileId,int fileVersion,String sha256,String contentType,long length) {}
    public record CommitProjection(String executionId,String runId,String manifestId,String manifestSha256,List<CommitItem> items) {
        public CommitProjection{items=List.copyOf(items);}
    }
    public record CommittedResult(String effectId,String resourceSha256,String commitReceiptId,long committedAt,CommitProjection projection) implements Result {}
    public record CloseProjection(Integer code,String signal,long observedAt) {}
    public record ProcessProjection(long pid,long startTime,String pidNamespace,boolean pinned,boolean pidfdExitObserved) {}
    public record RemainingProjection(long pid,long startTime,String pidNamespace) {}
    public record NamespaceProjection(String pidNamespace,long depth,boolean held,Long initPid,Long initStartTime,boolean pinned,boolean pidfdExitObserved) {}
    public record DrainProjection(String runToken,String disposition,boolean startupReleased,CloseProjection directExit,CloseProjection directClose,List<NamespaceProjection> namespaces,List<ProcessProjection> processes,List<RemainingProjection> remaining,CloseProjection helperClose) {
        public DrainProjection{namespaces=List.copyOf(namespaces);processes=List.copyOf(processes);remaining=List.copyOf(remaining);}
    }
    /** Wire evidence is never an authenticated kernel handle or a settlement proof. */
    public sealed interface Evidence permits LocalCommitObservation,WorkspaceTreeObservation,ProcessDrainObservation,RequestUncertain,ServerCommitObservation {}
    public record LocalCommitObservation(String effectId,String resourceSha256,String runToken,String markerSha256,String manifestSha256,long device,long inode,long parentDevice,long parentInode,String contentSha256,boolean parentFsynced,long commitAdmittedAt,long observedAt) implements Evidence {}
    public record WorkspaceTreeObservation(String effectId,String resourceSha256,String runToken,String workspaceKey,String baseTreeSha256,String treeSha256,String manifestSha256,long observedAt) implements Evidence {}
    public record ProcessDrainObservation(String runToken,DrainProjection proof,long observedAt) implements Evidence {}
    public record RequestUncertain(String requestId,String requestSha256,String runToken,String reason,long observedAt) implements Evidence {}
    public record ServerCommitObservation(String effectId,String resourceSha256,String commitReceiptId,String commitReceiptSha256,CommitProjection projection) implements Evidence {}
    public record ObservedResult(String effectId,String resourceSha256,String category,Evidence observation) implements Result {}
    public sealed interface Proof permits RenewalMapping,RetirementBarrier,ExecutionAdmissionBarrier,EffectReceiptProof,SettlementProof,ExecutionEffectsProof,EffectSuccessReference {}
    public sealed interface SettlementProof extends Proof permits ExecutionSettlementProof,ExecutionSettlementGraphProof {
        String runtimeDispatchId();String runToken();byte[] terminalAck();byte[] settlementReceipt();
    }
    public record EffectSuccessReference(String runtimeDispatchId,String runToken,String effectId,Kind effectKind,Phase phase,String decision,String resourceSha256,String originalRequestId,String originalRequestSha256,String originalResponseSha256,String executionGrantSha256) implements Proof {}
    public record RenewalMapping(String requestId,String requestSha256,String runtimeDispatchId,String startedReceiptId,String startedReceiptSha256,String previousExecutionGrantId,String previousExecutionGrantSha256,String mappingEventId,String mappingEventSha256,byte[] response) implements Proof {
        public RenewalMapping{response=response==null?null:response.clone();}
        @Override public byte[] response(){return response==null?null:response.clone();}
    }
    public record RetirementBarrier(String requestId,String requestSha256,String runtimeDispatchId,String retiredHolderGenerationFactId,String retiredHolderGenerationSnapshotSha256,String currentHolderGenerationFactId,String currentHolderGenerationSnapshotSha256,String barrierEventId,String barrierEventSha256,long checkedAt) implements Proof {}
    public record ExecutionAdmissionBarrier(String runtimeDispatchId,String startedReceiptId,String startedReceiptSha256,String originEventId,String originEventSha256,String barrierEventId,String barrierEventSha256,long checkedAt) implements Proof {}
    public record EffectReceiptProof(String effectId,String resourceSha256,byte[] receipt) implements Proof {
        public EffectReceiptProof{receipt=receipt==null?null:receipt.clone();}
        @Override public byte[] receipt(){return receipt==null?null:receipt.clone();}
    }
    public record ExecutionEffectsProof(String runtimeDispatchId,String runToken,List<EffectReceiptProof> effects) implements Proof {
        public ExecutionEffectsProof{effects=List.copyOf(effects);}
    }
    public record ExecutionSettlementProof(String runtimeDispatchId,String runToken,byte[] terminalAck,byte[] settlementReceipt,List<EffectReceiptProof> effects) implements SettlementProof {
        public ExecutionSettlementProof{terminalAck=terminalAck==null?null:terminalAck.clone();settlementReceipt=settlementReceipt==null?null:settlementReceipt.clone();effects=List.copyOf(effects);}
        @Override public byte[] terminalAck(){return terminalAck==null?null:terminalAck.clone();}
        @Override public byte[] settlementReceipt(){return settlementReceipt==null?null:settlementReceipt.clone();}
    }
    public record ExecutionSettlementGraphProof(String runtimeDispatchId,String runToken,byte[] terminalAck,byte[] settlementReceipt,long effectCount,String effectGraphSha256) implements SettlementProof {
        public ExecutionSettlementGraphProof{terminalAck=terminalAck==null?null:terminalAck.clone();settlementReceipt=settlementReceipt==null?null:settlementReceipt.clone();}
        @Override public byte[] terminalAck(){return terminalAck==null?null:terminalAck.clone();}
        @Override public byte[] settlementReceipt(){return settlementReceipt==null?null:settlementReceipt.clone();}
    }
    public record Reconciliation(Subject subjectKind,String subjectId,String disposition,String originalRequestSha256,Proof proof) implements Result {}
    public record Receipt(String messageType,String requestId,String runtimeDispatchId,String receiptId,String requestSha256,String decision,long serverAt,Result result) {}

    public static byte[] encodeResource(Kind kind,Phase phase,Resource resource){
        if(kind==null||phase==null||!PHASES.get(kind).contains(phase)||resource==null)throw invalid();
        ObjectNode out=JSON.createObjectNode();
        switch(kind){
            case SKILL_PACKAGE_READ -> {if(!(resource instanceof SkillRead r))throw invalid();skill(out,r.identity());chunk(r.offset(),r.length());put(out,"offset",r.offset(),"length",r.length());}
            case SKILL_ACTIVATE,SKILL_RESULT -> {if(!(resource instanceof SkillIdentity r))throw invalid();skill(out,r);}
            case WORKSPACE_PREPARE -> {if(!(resource instanceof WorkspacePrepare r))throw invalid();task(out,r.taskId(),r.workItemId(),true);exact(r.workspaceKey());if(r.baseTreeSha256()==null&&phase!=Phase.DESCRIBE)throw invalid();nullableHash(r.baseTreeSha256());put(out,"workspaceKey",r.workspaceKey(),"baseTreeSha256",r.baseTreeSha256());}
            case WORKSPACE_MUTATE -> {if(!(resource instanceof WorkspaceMutate r))throw invalid();task(out,r.taskId(),r.workItemId(),true);exact(r.workspaceKey());hash(r.baseTreeSha256());hash(r.manifestSha256());put(out,"workspaceKey",r.workspaceKey(),"baseTreeSha256",r.baseTreeSha256(),"manifestSha256",r.manifestSha256());}
            case FILE_DESCRIBE -> {if(!(resource instanceof FileDescribe r))throw invalid();task(out,r.taskId(),r.workItemId(),false);}
            case FILE_INPUT_READ -> {if(!(resource instanceof FileRead r))throw invalid();file(out,r.executionId(),r.taskId(),r.workItemId(),r.runId());exact(r.inputId());chunk(r.offset(),r.length());put(out,"inputId",r.inputId(),"offset",r.offset(),"length",r.length());}
            case FILE_STAGE -> {if(!(resource instanceof FileStage r))throw invalid();file(out,r.executionId(),r.taskId(),r.workItemId(),r.runId());exact(r.outputId());hash(r.contentSha256());chunk(r.offset(),r.length());nonnegative(r.totalLength());if(r.offset()>r.totalLength()||r.length()>r.totalLength()-r.offset())throw invalid();put(out,"outputId",r.outputId(),"contentSha256",r.contentSha256(),"totalLength",r.totalLength(),"offset",r.offset(),"length",r.length());}
            case FILE_COMMIT -> {if(!(resource instanceof FileCommit r))throw invalid();file(out,r.executionId(),r.taskId(),r.workItemId(),r.runId());exact(r.manifestId());hash(r.manifestSha256());put(out,"manifestId",r.manifestId(),"manifestSha256",r.manifestSha256());}
            case PROCESS_START -> {if(!(resource instanceof ProcessStart r))throw invalid();task(out,r.taskId(),r.workItemId(),true);engine(r.engine());exact(r.workspaceKey());put(out,"engine",r.engine(),"workspaceKey",r.workspaceKey());}
            case PROCESS_EXIT -> {if(!(resource instanceof ProcessExit r))throw invalid();task(out,r.taskId(),r.workItemId(),true);engine(r.engine());exact(r.workspaceKey());positive(r.pid());positive(r.startTime());if(r.signal()!=null&&!SIGNALS.contains(r.signal()))throw invalid();put(out,"engine",r.engine(),"workspaceKey",r.workspaceKey(),"pid",r.pid(),"startTime",r.startTime());if(r.exitCode()==null)out.putNull("exitCode");else out.put("exitCode",r.exitCode());put(out,"signal",r.signal());}
        }
        return encoded(out,RESOURCE_CAP);
    }
    public static Resource decodeResource(Kind kind,Phase phase,byte[] raw){
        JsonNode n=parse(raw,RESOURCE_CAP);Resource value;
        switch(kind){
            case SKILL_PACKAGE_READ -> value=new SkillRead(skill(n),decimal(n,"offset",false),decimal(n,"length",false));
            case SKILL_ACTIVATE,SKILL_RESULT -> value=skill(n);
            case WORKSPACE_PREPARE -> value=new WorkspacePrepare(string(n,"taskId"),nullableString(n,"workItemId"),string(n,"workspaceKey"),nullableString(n,"baseTreeSha256"));
            case WORKSPACE_MUTATE -> value=new WorkspaceMutate(string(n,"taskId"),nullableString(n,"workItemId"),string(n,"workspaceKey"),string(n,"baseTreeSha256"),string(n,"manifestSha256"));
            case FILE_DESCRIBE -> value=new FileDescribe(string(n,"taskId"),string(n,"workItemId"));
            case FILE_INPUT_READ -> value=new FileRead(string(n,"executionId"),string(n,"taskId"),string(n,"workItemId"),string(n,"runId"),string(n,"inputId"),decimal(n,"offset",false),decimal(n,"length",false));
            case FILE_STAGE -> value=new FileStage(string(n,"executionId"),string(n,"taskId"),string(n,"workItemId"),string(n,"runId"),string(n,"outputId"),string(n,"contentSha256"),decimal(n,"totalLength",false),decimal(n,"offset",false),decimal(n,"length",false));
            case FILE_COMMIT -> value=new FileCommit(string(n,"executionId"),string(n,"taskId"),string(n,"workItemId"),string(n,"runId"),string(n,"manifestId"),string(n,"manifestSha256"));
            case PROCESS_START -> value=new ProcessStart(string(n,"taskId"),nullableString(n,"workItemId"),string(n,"engine"),string(n,"workspaceKey"));
            case PROCESS_EXIT -> value=new ProcessExit(string(n,"taskId"),nullableString(n,"workItemId"),string(n,"engine"),string(n,"workspaceKey"),decimal(n,"pid",true),decimal(n,"startTime",true),nullableInteger(n,"exitCode"),nullableString(n,"signal"));
            default -> throw invalid();
        }
        canonical(raw,encodeResource(kind,phase,value));return value;
    }
    public static byte[] encode(Request request){
        ObjectNode n=JSON.createObjectNode();n.put("schemaVersion",2);
        if(request instanceof Inspect r){n.put("messageType","command.execution.control.inspect");grant(n,r.grant());}
        else if(request instanceof EffectRequest r){
            n.put("messageType","command.execution.effect.request");grant(n,r.grant());uuid(r.runToken());effect(r.effectId());byte[] resource=encodeResource(r.effectKind(),r.phase(),r.resource());
            put(n,"runToken",r.runToken(),"effectId",r.effectId(),"effectKind",r.effectKind().name(),"phase",r.phase().name(),"resourceBytesBase64",b64(resource),"resourceSha256",hex(sha256(resource)));
            byte[] content=r.content();if(r.phase()==Phase.STAGE){if(content==null||content.length>CHUNK_CAP||!(r.resource() instanceof FileStage f)||content.length!=f.length())throw invalid();if(f.offset()==0&&f.length()==f.totalLength()&&!hex(sha256(content)).equals(f.contentSha256()))throw invalid();}
            else if(content!=null)validateLocalContent(r,content);put(n,"contentBytesBase64",content==null?null:b64(content));
        } else if(request instanceof Reconcile r){
            n.put("messageType","command.execution.reconcile.request");uuid(r.requestId());uuid(r.runtimeDispatchId());Objects.requireNonNull(r.subjectKind());if(Set.of(Subject.EFFECT,Subject.EFFECT_SUCCESS).contains(r.subjectKind()))effect(r.subjectId());else uuid(r.subjectId());
            byte[] original=r.originalRequest(),evidence=r.evidence();if(original!=null&&(original.length==0||original.length>98304)||r.subjectKind()==Subject.EFFECT_SUCCESS&&evidence!=null)throw invalid();if(evidence!=null)decodeEvidence(evidence);
            if(original!=null){
                if(r.subjectKind()==Subject.RENEWAL){var q=AgentRuntimeV2ExecutionCodec.decodeRenew(original);if(!q.runtimeDispatchId().equals(r.runtimeDispatchId())||!q.renewalRequestId().equals(r.subjectId())||q.renewalRequestId().equals(r.requestId()))throw invalid();}
                else if(r.subjectKind()==Subject.EXECUTION){var q=AgentRuntimeV2ExecutionCodec.decodeAck(original);if(!q.runtimeDispatchId().equals(r.runtimeDispatchId())||!q.runtimeDispatchId().equals(r.subjectId()))throw invalid();}
                else{Request q=decode(original);if(!(q instanceof EffectRequest e)||!e.grant().runtimeDispatchId().equals(r.runtimeDispatchId())||!e.effectId().equals(r.subjectId()))throw invalid();}
            }
            put(n,"requestId",r.requestId(),"runtimeDispatchId",r.runtimeDispatchId(),"subjectKind",r.subjectKind().name(),"subjectId",r.subjectId(),"originalRequestBytesBase64",original==null?null:b64(original),"originalRequestSha256",original==null?null:hex(sha256(original)),"evidenceBytesBase64",evidence==null?null:b64(evidence),"evidenceSha256",evidence==null?null:hex(sha256(evidence)));
        } else throw invalid();return encoded(n,FRAME_CAP);
    }
    public static Request decode(byte[] raw){
        JsonNode n=parse(raw,FRAME_CAP);if(!n.path("schemaVersion").isInt()||n.path("schemaVersion").intValue()!=2)throw invalid();String type=string(n,"messageType");Request value;
        switch(type){
            case "command.execution.control.inspect" -> value=new Inspect(grant(n));
            case "command.execution.effect.request" -> {Kind kind=enumValue(Kind.class,string(n,"effectKind"));Phase phase=enumValue(Phase.class,string(n,"phase"));byte[] resource=pair(n,"resourceBytesBase64","resourceSha256",RESOURCE_CAP,false);value=new EffectRequest(grant(n),string(n,"runToken"),string(n,"effectId"),kind,phase,decodeResource(kind,phase,resource),base64Nullable(n,"contentBytesBase64",CHUNK_CAP,true));}
            case "command.execution.reconcile.request" -> value=new Reconcile(string(n,"requestId"),string(n,"runtimeDispatchId"),enumValue(Subject.class,string(n,"subjectKind")),string(n,"subjectId"),pair(n,"originalRequestBytesBase64","originalRequestSha256",98304,true),pair(n,"evidenceBytesBase64","evidenceSha256",65536,true));
            default -> throw invalid();
        }
        canonical(raw,encode(value));return value;
    }
    public static byte[] encodeControl(Control c){
        Objects.requireNonNull(c.access());uuid(c.runtimeBindingId());exact(c.runtimeInstanceId());nonnegative(c.holderBindingGeneration());uuid(c.holderGenerationFactId());hash(c.holderGenerationSnapshotSha256());uuid(c.executionGrantId());hash(c.executionGrantSha256());uuid(c.currentLeaseFactId());nonnegative(c.leaseRevision());positive(c.leaseUntil());nonnegative(c.tenantControlRevision());nonnegative(c.moduleModeRevision());nonnegative(c.deploymentRegistrationRevision());
        in(c.tenantState(),"ENABLED","AUTH_CLOSED","WRITE_CLOSED_CLAIM_CLOSED","DRAINING","DISABLED");in(c.moduleState(),"LEGACY_CLIENT","DOMAIN","QUIESCING");in(c.deploymentState(),"ACTIVE","DRAINING","DISABLED","REVOKED");
        if(c.drainOperationId()!=null)uuid(c.drainOperationId());if(c.cutoffAt()!=null)positive(c.cutoffAt());if(c.deadlineAt()!=null)positive(c.deadlineAt());positive(c.checkedAt());positive(c.validUntil());
        if((c.access()!=Access.DENIED&&c.validUntil()<c.checkedAt())||c.validUntil()-c.checkedAt()>1000||c.validUntil()>c.leaseUntil()||c.deadlineAt()!=null&&c.validUntil()>c.deadlineAt()||(c.drainOperationId()==null)!=(c.cutoffAt()==null)||(c.drainOperationId()==null)!=(c.deadlineAt()==null))throw invalid();
        if(c.access()==Access.OPEN&&(c.drainOperationId()!=null||!Set.of("ENABLED","AUTH_CLOSED").contains(c.tenantState())||!"DOMAIN".equals(c.moduleState())||!"ACTIVE".equals(c.deploymentState())))throw invalid();
        ObjectNode n=JSON.createObjectNode();put(n,"access",c.access().name(),"runtimeBindingId",c.runtimeBindingId(),"runtimeInstanceId",c.runtimeInstanceId(),"holderBindingGeneration",c.holderBindingGeneration(),"holderGenerationFactId",c.holderGenerationFactId(),"holderGenerationSnapshotSha256",c.holderGenerationSnapshotSha256(),"executionGrantId",c.executionGrantId(),"executionGrantSha256",c.executionGrantSha256(),"currentLeaseFactId",c.currentLeaseFactId(),"leaseRevision",c.leaseRevision(),"leaseUntil",c.leaseUntil(),"tenantControlRevision",c.tenantControlRevision(),"moduleModeRevision",c.moduleModeRevision(),"deploymentRegistrationRevision",c.deploymentRegistrationRevision(),"tenantState",c.tenantState(),"moduleState",c.moduleState(),"deploymentState",c.deploymentState(),"drainOperationId",c.drainOperationId(),"cutoffAt",c.cutoffAt(),"deadlineAt",c.deadlineAt(),"checkedAt",c.checkedAt(),"validUntil",c.validUntil());return encoded(n,RESOURCE_CAP);
    }
    public static Control decodeControl(byte[] raw){
        JsonNode n=parse(raw,RESOURCE_CAP);var c=new Control(enumValue(Access.class,string(n,"access")),string(n,"runtimeBindingId"),string(n,"runtimeInstanceId"),decimal(n,"holderBindingGeneration",false),string(n,"holderGenerationFactId"),string(n,"holderGenerationSnapshotSha256"),string(n,"executionGrantId"),string(n,"executionGrantSha256"),string(n,"currentLeaseFactId"),decimal(n,"leaseRevision",false),decimal(n,"leaseUntil",true),decimal(n,"tenantControlRevision",false),decimal(n,"moduleModeRevision",false),decimal(n,"deploymentRegistrationRevision",false),string(n,"tenantState"),string(n,"moduleState"),string(n,"deploymentState"),nullableString(n,"drainOperationId"),nullableDecimal(n,"cutoffAt"),nullableDecimal(n,"deadlineAt"),decimal(n,"checkedAt",true),decimal(n,"validUntil",true));canonical(raw,encodeControl(c));return c;
    }
    public static byte[] encodeDescriptor(String branch,Descriptor descriptor){
        ObjectNode n=JSON.createObjectNode();
        if("CODEX".equals(branch)&&descriptor instanceof CodexDescriptor d){
            encodeResource(Kind.WORKSPACE_PREPARE,Phase.DESCRIBE,new WorkspacePrepare(d.taskId(),d.workItemId(),d.workspaceKey(),d.baseTreeSha256()));engine(d.engine());
            put(n,"taskId",d.taskId(),"workItemId",d.workItemId(),"engine",d.engine(),"workspaceKey",d.workspaceKey(),"baseTreeSha256",d.baseTreeSha256());
        }else if("SKILL".equals(branch)&&descriptor instanceof SkillDescriptor d){skill(n,d.identity());positive(d.packageSize());put(n,"packageSize",d.packageSize());}
        else if("WORKSPACE_FILE".equals(branch)&&descriptor instanceof FileDescriptor d){
            safeFileId(d.taskId());safeFileId(d.runId());if(d.inputManifest().size()>128||d.outputManifest().size()>128)throw invalid();put(n,"taskId",d.taskId(),"runId",d.runId());
            Set<String> identities=new HashSet<>(),paths=new HashSet<>();ArrayNode inputs=n.putArray("inputManifest");
            for(FileInput input:d.inputManifest()){
                safeFileId(input.inputId());relativePath(input.relativePath());safeFileLength(input.length());hash(input.sha256());
                if(!identities.add("input:"+input.inputId())||!input.downloadPath().equals("/internal/agent/tasks/"+d.taskId()+"/runs/"+d.runId()+"/inputs/"+input.inputId()+"/content"))throw invalid();addManifestPath(paths,input.relativePath());
                ObjectNode item=inputs.addObject();put(item,"inputId",input.inputId(),"relativePath",input.relativePath(),"downloadPath",input.downloadPath());item.put("length",input.length());put(item,"sha256",input.sha256());
            }
            ArrayNode outputs=n.putArray("outputManifest");for(FileOutput output:d.outputManifest()){
                safeFileId(output.outputId());relativePath(output.relativePath());safeFileLength(output.maxLength());mediaType(output.contentType());
                if(!identities.add("output:"+output.outputId())||!output.uploadPath().equals("/internal/agent/tasks/"+d.taskId()+"/runs/"+d.runId()+"/outputs/"+output.outputId()+"/content"))throw invalid();addManifestPath(paths,output.relativePath());
                ObjectNode item=outputs.addObject();put(item,"outputId",output.outputId(),"relativePath",output.relativePath(),"contentType",output.contentType());item.put("maxLength",output.maxLength());put(item,"uploadPath",output.uploadPath());
            }
        }else throw invalid();return encoded(n,65536);
    }
    public static Descriptor decodeDescriptor(String branch,byte[] raw){
        JsonNode n=parse(raw,65536);Descriptor d;
        if("CODEX".equals(branch))d=new CodexDescriptor(string(n,"taskId"),nullableString(n,"workItemId"),string(n,"engine"),string(n,"workspaceKey"),nullableString(n,"baseTreeSha256"));
        else if("SKILL".equals(branch))d=new SkillDescriptor(skill(n),decimal(n,"packageSize",true));
        else if("WORKSPACE_FILE".equals(branch)){
            List<FileInput> inputs=new ArrayList<>();for(JsonNode item:array(n,"inputManifest",128))inputs.add(new FileInput(string(item,"inputId"),string(item,"relativePath"),string(item,"downloadPath"),safeNumber(item,"length"),string(item,"sha256")));
            List<FileOutput> outputs=new ArrayList<>();for(JsonNode item:array(n,"outputManifest",128))outputs.add(new FileOutput(string(item,"outputId"),string(item,"relativePath"),string(item,"contentType"),safeNumber(item,"maxLength"),string(item,"uploadPath")));
            d=new FileDescriptor(string(n,"taskId"),string(n,"runId"),inputs,outputs);
        }else throw invalid();canonical(raw,encodeDescriptor(branch,d));return d;
    }
    public static byte[] encodeCommitProjection(CommitProjection projection){
        if(projection==null)throw invalid();exact(projection.executionId());exact(projection.runId());exact(projection.manifestId());hash(projection.manifestSha256());if(projection.items().isEmpty()||projection.items().size()>128)throw invalid();
        ObjectNode n=JSON.createObjectNode();put(n,"kind","FILE_COMMIT","executionId",projection.executionId(),"runId",projection.runId(),"manifestId",projection.manifestId(),"manifestSha256",projection.manifestSha256(),"state","COMMITTED");
        Set<String> outputs=new HashSet<>(),files=new HashSet<>();ArrayNode items=n.putArray("items");for(CommitItem item:projection.items()){
            exact(item.outputId());exact(item.fileId());if(item.fileVersion()<=0||!outputs.add(item.outputId())||!files.add(item.fileId()+":"+item.fileVersion()))throw invalid();hash(item.sha256());mediaType(item.contentType());nonnegative(item.length());
            ObjectNode out=items.addObject();put(out,"outputId",item.outputId(),"fileId",item.fileId());out.put("fileVersion",item.fileVersion());put(out,"sha256",item.sha256(),"contentType",item.contentType(),"length",item.length());
        }return encoded(n,65536);
    }
    public static CommitProjection decodeCommitProjection(byte[] raw){
        JsonNode n=parse(raw,65536);in(string(n,"kind"),"FILE_COMMIT");in(string(n,"state"),"COMMITTED");List<CommitItem> items=new ArrayList<>();
        for(JsonNode item:array(n,"items",128))items.add(new CommitItem(string(item,"outputId"),string(item,"fileId"),requiredInteger(item,"fileVersion"),string(item,"sha256"),string(item,"contentType"),decimal(item,"length",false)));
        var value=new CommitProjection(string(n,"executionId"),string(n,"runId"),string(n,"manifestId"),string(n,"manifestSha256"),items);canonical(raw,encodeCommitProjection(value));return value;
    }
    public static byte[] encodeDrainProjection(DrainProjection d){
        if(d==null)throw invalid();uuid(d.runToken());in(d.disposition(),"DRAINED_KNOWN","EFFECT_UNKNOWN");if(d.namespaces().size()>256||d.processes().size()>256||d.remaining().size()>256)throw invalid();
        ObjectNode n=JSON.createObjectNode();put(n,"runToken",d.runToken(),"disposition",d.disposition());n.put("startupReleased",d.startupReleased());close(n,"directExit",d.directExit());close(n,"directClose",d.directClose());
        Set<String> processes=new HashSet<>(),namespaces=new HashSet<>(),remaining=new HashSet<>();for(ProcessProjection p:d.processes()){
            positive(p.pid());positive(p.startTime());namespace(p.pidNamespace());if(!processes.add(processIdentity(p.pid(),p.startTime(),p.pidNamespace()))||p.pidfdExitObserved()&&!p.pinned())throw invalid();
        }
        ArrayNode ns=n.putArray("namespaces");for(NamespaceProjection s:d.namespaces()){
            namespace(s.pidNamespace());positive(s.depth());if(!namespaces.add(s.pidNamespace())||(s.initPid()==null)!=(s.initStartTime()==null))throw invalid();
            if(s.initPid()==null){if(s.pinned()||s.pidfdExitObserved())throw invalid();}else{positive(s.initPid());positive(s.initStartTime());ProcessProjection init=d.processes().stream().filter(p->p.pid()==s.initPid()&&p.startTime()==s.initStartTime()&&p.pidNamespace().equals(s.pidNamespace())).findFirst().orElseThrow(AgentRuntimeV2EffectCodec::invalid);if(init.pinned()!=s.pinned()||init.pidfdExitObserved()!=s.pidfdExitObserved())throw invalid();}
            ObjectNode o=ns.addObject();put(o,"pidNamespace",s.pidNamespace(),"depth",s.depth());o.put("held",s.held());put(o,"initPid",s.initPid(),"initStartTime",s.initStartTime());o.put("pinned",s.pinned());o.put("pidfdExitObserved",s.pidfdExitObserved());
        }
        ArrayNode ps=n.putArray("processes");for(ProcessProjection p:d.processes()){ObjectNode o=ps.addObject();put(o,"pid",p.pid(),"startTime",p.startTime(),"pidNamespace",p.pidNamespace());o.put("pinned",p.pinned());o.put("pidfdExitObserved",p.pidfdExitObserved());}
        ArrayNode rs=n.putArray("remaining");for(RemainingProjection p:d.remaining()){
            positive(p.pid());positive(p.startTime());namespace(p.pidNamespace());String identity=processIdentity(p.pid(),p.startTime(),p.pidNamespace());if(!processes.contains(identity)||!remaining.add(identity))throw invalid();put(rs.addObject(),"pid",p.pid(),"startTime",p.startTime(),"pidNamespace",p.pidNamespace());
        }
        close(n,"helperClose",d.helperClose());if("DRAINED_KNOWN".equals(d.disposition())){
            long depth=d.namespaces().stream().mapToLong(NamespaceProjection::depth).min().orElseThrow(AgentRuntimeV2EffectCodec::invalid);
            if(d.directClose()==null||d.helperClose()==null||!Objects.equals(d.helperClose().code(),0)||d.helperClose().signal()!=null||!d.remaining().isEmpty()||d.processes().stream().anyMatch(p->p.pinned()&&!p.pidfdExitObserved())||d.namespaces().stream().filter(s->s.depth()==depth).anyMatch(s->!s.held()||s.initPid()==null||!s.pinned()||!s.pidfdExitObserved()))throw invalid();
        }return encoded(n,65536);
    }
    public static DrainProjection decodeDrainProjection(byte[] raw){
        JsonNode n=parse(raw,65536);List<NamespaceProjection> ns=new ArrayList<>();List<ProcessProjection> ps=new ArrayList<>();List<RemainingProjection> rs=new ArrayList<>();
        for(JsonNode s:array(n,"namespaces",256))ns.add(new NamespaceProjection(string(s,"pidNamespace"),decimal(s,"depth",true),booleanValue(s,"held"),nullableDecimal(s,"initPid"),nullableDecimal(s,"initStartTime"),booleanValue(s,"pinned"),booleanValue(s,"pidfdExitObserved")));
        for(JsonNode p:array(n,"processes",256))ps.add(new ProcessProjection(decimal(p,"pid",true),decimal(p,"startTime",true),string(p,"pidNamespace"),booleanValue(p,"pinned"),booleanValue(p,"pidfdExitObserved")));
        for(JsonNode p:array(n,"remaining",256))rs.add(new RemainingProjection(decimal(p,"pid",true),decimal(p,"startTime",true),string(p,"pidNamespace")));
        var d=new DrainProjection(string(n,"runToken"),string(n,"disposition"),booleanValue(n,"startupReleased"),close(n,"directExit"),close(n,"directClose"),ns,ps,rs,close(n,"helperClose"));canonical(raw,encodeDrainProjection(d));return d;
    }
    public static byte[] encodeEvidence(Evidence e){
        ObjectNode n=JSON.createObjectNode();
        if(e instanceof LocalCommitObservation o){
            observation(o.effectId(),o.resourceSha256());uuid(o.runToken());hash(o.markerSha256());hash(o.manifestSha256());nonnegative(o.device());positive(o.inode());nonnegative(o.parentDevice());positive(o.parentInode());hash(o.contentSha256());if(!o.parentFsynced())throw invalid();nonnegative(o.commitAdmittedAt());nonnegative(o.observedAt());if(o.commitAdmittedAt()>o.observedAt())throw invalid();
            put(n,"kind","LOCAL_COMMIT_OBSERVED","effectId",o.effectId(),"resourceSha256",o.resourceSha256(),"runToken",o.runToken(),"markerSha256",o.markerSha256(),"manifestSha256",o.manifestSha256(),"device",o.device(),"inode",o.inode(),"parentDevice",o.parentDevice(),"parentInode",o.parentInode(),"contentSha256",o.contentSha256());n.put("parentFsynced",true);put(n,"commitAdmittedAt",o.commitAdmittedAt(),"observedAt",o.observedAt());
        }else if(e instanceof WorkspaceTreeObservation o){
            observation(o.effectId(),o.resourceSha256());uuid(o.runToken());exact(o.workspaceKey());hash(o.baseTreeSha256());hash(o.treeSha256());hash(o.manifestSha256());nonnegative(o.observedAt());put(n,"kind","WORKSPACE_TREE_OBSERVED","effectId",o.effectId(),"resourceSha256",o.resourceSha256(),"runToken",o.runToken(),"workspaceKey",o.workspaceKey(),"baseTreeSha256",o.baseTreeSha256(),"treeSha256",o.treeSha256(),"manifestSha256",o.manifestSha256(),"observedAt",o.observedAt());
        }else if(e instanceof ProcessDrainObservation o){
            uuid(o.runToken());if(!o.runToken().equals(o.proof().runToken()))throw invalid();nonnegative(o.observedAt());byte[] proof=encodeDrainProjection(o.proof());put(n,"kind","PROCESS_DRAIN","runToken",o.runToken(),"proofBytesBase64",b64(proof),"proofSha256",hex(sha256(proof)),"observedAt",o.observedAt());
        }else if(e instanceof RequestUncertain o){uuid(o.requestId());hash(o.requestSha256());uuid(o.runToken());in(o.reason(),"TRANSPORT_LOST","CANCELLED","RECOVERY","TIMEOUT");nonnegative(o.observedAt());put(n,"kind","REQUEST_UNCERTAIN","requestId",o.requestId(),"requestSha256",o.requestSha256(),"runToken",o.runToken(),"reason",o.reason(),"observedAt",o.observedAt());}
        else if(e instanceof ServerCommitObservation o){observation(o.effectId(),o.resourceSha256());uuid(o.commitReceiptId());hash(o.commitReceiptSha256());byte[] result=encodeCommitProjection(o.projection());put(n,"kind","SERVER_COMMIT","effectId",o.effectId(),"resourceSha256",o.resourceSha256(),"commitReceiptId",o.commitReceiptId(),"commitReceiptSha256",o.commitReceiptSha256(),"resultBytesBase64",b64(result),"resultSha256",hex(sha256(result)));}
        else throw invalid();return encoded(n,65536);
    }
    public static Evidence decodeEvidence(byte[] raw){
        JsonNode n=parse(raw,65536);Evidence e=switch(string(n,"kind")){
            case "LOCAL_COMMIT_OBSERVED" -> new LocalCommitObservation(string(n,"effectId"),string(n,"resourceSha256"),string(n,"runToken"),string(n,"markerSha256"),string(n,"manifestSha256"),decimal(n,"device",false),decimal(n,"inode",true),decimal(n,"parentDevice",false),decimal(n,"parentInode",true),string(n,"contentSha256"),booleanValue(n,"parentFsynced"),decimal(n,"commitAdmittedAt",false),decimal(n,"observedAt",false));
            case "WORKSPACE_TREE_OBSERVED" -> new WorkspaceTreeObservation(string(n,"effectId"),string(n,"resourceSha256"),string(n,"runToken"),string(n,"workspaceKey"),string(n,"baseTreeSha256"),string(n,"treeSha256"),string(n,"manifestSha256"),decimal(n,"observedAt",false));
            case "PROCESS_DRAIN" -> new ProcessDrainObservation(string(n,"runToken"),decodeDrainProjection(pair(n,"proofBytesBase64","proofSha256",65536,false)),decimal(n,"observedAt",false));
            case "REQUEST_UNCERTAIN" -> new RequestUncertain(string(n,"requestId"),string(n,"requestSha256"),string(n,"runToken"),string(n,"reason"),decimal(n,"observedAt",false));
            case "SERVER_COMMIT" -> new ServerCommitObservation(string(n,"effectId"),string(n,"resourceSha256"),string(n,"commitReceiptId"),string(n,"commitReceiptSha256"),decodeCommitProjection(pair(n,"resultBytesBase64","resultSha256",65536,false)));
            default -> throw invalid();
        };canonical(raw,encodeEvidence(e));return e;
    }
    public static byte[] encodeEffectResult(String decision,Result result){
        ObjectNode n=JSON.createObjectNode();
        if("READ".equals(decision)&&result instanceof ReadResult r){chunk(r.offset(),r.length());nonnegative(r.totalLength());hash(r.sha256());byte[] content=r.content();if(content==null||content.length!=r.length()||r.offset()>r.totalLength()||r.length()>r.totalLength()-r.offset()||!hex(sha256(content)).equals(r.sha256()))throw invalid();put(n,"offset",r.offset(),"length",r.length(),"totalLength",r.totalLength(),"sha256",r.sha256(),"contentBytesBase64",b64(content));}
        else if("STAGED".equals(decision)&&result instanceof StagedResult r){exact(r.stagedId());hash(r.contentSha256());nonnegative(r.length());put(n,"stagedId",r.stagedId(),"contentSha256",r.contentSha256(),"length",r.length());}
        else if("ADMITTED".equals(decision)&&result instanceof AdmittedResult r){effect(r.effectId());hash(r.resourceSha256());nonnegative(r.admittedAt());nonnegative(r.validUntil());uuid(r.holderGenerationFactId());uuid(r.currentLeaseFactId());if(r.validUntil()<=r.admittedAt()||r.validUntil()-r.admittedAt()>1000)throw invalid();put(n,"effectId",r.effectId(),"resourceSha256",r.resourceSha256(),"admittedAt",r.admittedAt(),"validUntil",r.validUntil(),"holderGenerationFactId",r.holderGenerationFactId(),"currentLeaseFactId",r.currentLeaseFactId());}
        else if("SETTLED".equals(decision)&&result instanceof SettledResult r){effect(r.effectId());hash(r.resourceSha256());in(r.disposition(),"COMMITTED_PROVEN","NOT_RUN_PROVEN","SETTLED_KNOWN");if(r.commitReceiptId()!=null)uuid(r.commitReceiptId());if("COMMITTED_PROVEN".equals(r.disposition())&&r.commitReceiptId()==null||"NOT_RUN_PROVEN".equals(r.disposition())&&r.commitReceiptId()!=null)throw invalid();uuid(r.settlementReceiptId());nonnegative(r.settledAt());put(n,"effectId",r.effectId(),"resourceSha256",r.resourceSha256(),"disposition",r.disposition(),"commitReceiptId",r.commitReceiptId(),"settlementReceiptId",r.settlementReceiptId(),"settledAt",r.settledAt());}
        else if("DESCRIBED".equals(decision)&&result instanceof DescribedResult r){
            if(r.executionId()!=null)exact(r.executionId());if(r.workItemId()!=null)exact(r.workItemId());byte[] descriptor=encodeDescriptor(r.branch(),r.descriptor());
            if("WORKSPACE_FILE".equals(r.branch())&&(r.executionId()==null||r.workItemId()==null)||"CODEX".equals(r.branch())&&(r.executionId()!=null||!Objects.equals(((CodexDescriptor)r.descriptor()).workItemId(),r.workItemId()))||"SKILL".equals(r.branch())&&(r.executionId()!=null||r.workItemId()!=null))throw invalid();
            put(n,"branch",r.branch(),"executionId",r.executionId(),"workItemId",r.workItemId(),"descriptorBytesBase64",b64(descriptor),"descriptorSha256",hex(sha256(descriptor)));
        }else if("COMMITTED".equals(decision)&&result instanceof CommittedResult r){observation(r.effectId(),r.resourceSha256());uuid(r.commitReceiptId());positive(r.committedAt());byte[] projection=encodeCommitProjection(r.projection());put(n,"effectId",r.effectId(),"resourceSha256",r.resourceSha256(),"commitReceiptId",r.commitReceiptId(),"committedAt",r.committedAt(),"resultBytesBase64",b64(projection),"resultSha256",hex(sha256(projection)));}
        else if(Set.of("OBSERVED","UNKNOWN").contains(decision)&&result instanceof ObservedResult r){
            observation(r.effectId(),r.resourceSha256());in(r.category(),"SERVER_COMMIT","LOCAL_COMMIT_OBSERVED","PROCESS_DRAIN","ADMITTED_IN_FLIGHT","EXTERNAL_UNKNOWN");byte[] evidence=encodeEvidence(r.observation());
            String observedEffect=null,observedHash=null;if(r.observation() instanceof LocalCommitObservation o){observedEffect=o.effectId();observedHash=o.resourceSha256();}else if(r.observation() instanceof WorkspaceTreeObservation o){observedEffect=o.effectId();observedHash=o.resourceSha256();}else if(r.observation() instanceof ServerCommitObservation o){observedEffect=o.effectId();observedHash=o.resourceSha256();}
            if(observedEffect!=null&&(!observedEffect.equals(r.effectId())||!observedHash.equals(r.resourceSha256())))throw invalid();
            if("SERVER_COMMIT".equals(r.category())&&!(r.observation() instanceof ServerCommitObservation)||"LOCAL_COMMIT_OBSERVED".equals(r.category())&&!(r.observation() instanceof LocalCommitObservation||r.observation() instanceof WorkspaceTreeObservation)||"PROCESS_DRAIN".equals(r.category())&&!(r.observation() instanceof ProcessDrainObservation)||Set.of("ADMITTED_IN_FLIGHT","EXTERNAL_UNKNOWN").contains(r.category())&&!(r.observation() instanceof RequestUncertain))throw invalid();
            if("UNKNOWN".equals(decision)&&!Set.of("ADMITTED_IN_FLIGHT","EXTERNAL_UNKNOWN","PROCESS_DRAIN").contains(r.category())||"OBSERVED".equals(decision)&&Set.of("ADMITTED_IN_FLIGHT","EXTERNAL_UNKNOWN").contains(r.category()))throw invalid();
            if(r.observation() instanceof ProcessDrainObservation o&&"DRAINED_KNOWN".equals(o.proof().disposition())!= "OBSERVED".equals(decision))throw invalid();
            put(n,"effectId",r.effectId(),"resourceSha256",r.resourceSha256(),"category",r.category(),"observationBytesBase64",b64(evidence),"observationSha256",hex(sha256(evidence)));
        }
        else throw invalid();return encoded(n,FRAME_CAP);
    }
    public static Result decodeEffectResult(String decision,byte[] raw){
        JsonNode n=parse(raw,FRAME_CAP);Result result=switch(decision){
            case "READ" -> new ReadResult(decimal(n,"offset",false),decimal(n,"length",false),decimal(n,"totalLength",false),string(n,"sha256"),base64(string(n,"contentBytesBase64"),CHUNK_CAP,true));
            case "STAGED" -> new StagedResult(string(n,"stagedId"),string(n,"contentSha256"),decimal(n,"length",false));
            case "ADMITTED" -> new AdmittedResult(string(n,"effectId"),string(n,"resourceSha256"),decimal(n,"admittedAt",false),decimal(n,"validUntil",false),string(n,"holderGenerationFactId"),string(n,"currentLeaseFactId"));
            case "SETTLED" -> new SettledResult(string(n,"effectId"),string(n,"resourceSha256"),string(n,"disposition"),nullableString(n,"commitReceiptId"),string(n,"settlementReceiptId"),decimal(n,"settledAt",false));
            case "DESCRIBED" -> new DescribedResult(string(n,"branch"),nullableString(n,"executionId"),nullableString(n,"workItemId"),decodeDescriptor(string(n,"branch"),pair(n,"descriptorBytesBase64","descriptorSha256",65536,false)));
            case "COMMITTED" -> new CommittedResult(string(n,"effectId"),string(n,"resourceSha256"),string(n,"commitReceiptId"),decimal(n,"committedAt",true),decodeCommitProjection(pair(n,"resultBytesBase64","resultSha256",65536,false)));
            case "OBSERVED","UNKNOWN" -> new ObservedResult(string(n,"effectId"),string(n,"resourceSha256"),string(n,"category"),decodeEvidence(pair(n,"observationBytesBase64","observationSha256",65536,false)));
            default -> throw invalid();
        };canonical(raw,encodeEffectResult(decision,result));return result;
    }
    public static byte[] encodeProof(Proof proof){
        ObjectNode n=JSON.createObjectNode();
        if(proof instanceof RenewalMapping p){
            uuid(p.requestId());hash(p.requestSha256());uuid(p.runtimeDispatchId());uuid(p.startedReceiptId());hash(p.startedReceiptSha256());uuid(p.previousExecutionGrantId());hash(p.previousExecutionGrantSha256());uuid(p.mappingEventId());hash(p.mappingEventSha256());
            JsonNode response=renewalResponse(p.response());if(!string(response,"runtimeDispatchId").equals(p.runtimeDispatchId())||!string(response,"startedReceiptId").equals(p.startedReceiptId()))throw invalid();
            if("command.execution.grant".equals(string(response,"messageType"))){if(!"RENEWAL".equals(string(response,"grantKind"))||!string(response,"startedReceiptSha256").equals(p.startedReceiptSha256())||!string(response,"previousExecutionGrantId").equals(p.previousExecutionGrantId()))throw invalid();}
            else if(!string(response,"correlationId").equals(p.requestId()))throw invalid();
            put(n,"kind","RENEWAL_MAPPING","requestId",p.requestId(),"requestSha256",p.requestSha256(),"runtimeDispatchId",p.runtimeDispatchId(),"startedReceiptId",p.startedReceiptId(),"startedReceiptSha256",p.startedReceiptSha256(),"previousExecutionGrantId",p.previousExecutionGrantId(),"previousExecutionGrantSha256",p.previousExecutionGrantSha256(),"mappingEventId",p.mappingEventId(),"mappingEventSha256",p.mappingEventSha256(),"responseBytesBase64",b64(p.response()),"responseSha256",hex(sha256(p.response())));
        }else if(proof instanceof RetirementBarrier p){
            uuid(p.requestId());hash(p.requestSha256());uuid(p.runtimeDispatchId());uuid(p.retiredHolderGenerationFactId());hash(p.retiredHolderGenerationSnapshotSha256());uuid(p.currentHolderGenerationFactId());hash(p.currentHolderGenerationSnapshotSha256());uuid(p.barrierEventId());hash(p.barrierEventSha256());positive(p.checkedAt());if(p.retiredHolderGenerationFactId().equals(p.currentHolderGenerationFactId()))throw invalid();
            put(n,"kind","RETIREMENT_BARRIER","requestId",p.requestId(),"requestSha256",p.requestSha256(),"runtimeDispatchId",p.runtimeDispatchId(),"retiredHolderGenerationFactId",p.retiredHolderGenerationFactId(),"retiredHolderGenerationSnapshotSha256",p.retiredHolderGenerationSnapshotSha256(),"currentHolderGenerationFactId",p.currentHolderGenerationFactId(),"currentHolderGenerationSnapshotSha256",p.currentHolderGenerationSnapshotSha256(),"barrierEventId",p.barrierEventId(),"barrierEventSha256",p.barrierEventSha256(),"checkedAt",p.checkedAt());
        }else if(proof instanceof ExecutionAdmissionBarrier p){
            uuid(p.runtimeDispatchId());uuid(p.startedReceiptId());hash(p.startedReceiptSha256());uuid(p.originEventId());hash(p.originEventSha256());uuid(p.barrierEventId());hash(p.barrierEventSha256());positive(p.checkedAt());
            put(n,"kind","EXECUTION_ADMISSION_BARRIER","runtimeDispatchId",p.runtimeDispatchId(),"startedReceiptId",p.startedReceiptId(),"startedReceiptSha256",p.startedReceiptSha256(),"originEventId",p.originEventId(),"originEventSha256",p.originEventSha256(),"runToken",null);n.put("invocationConsumed",false);put(n,"barrierEventId",p.barrierEventId(),"barrierEventSha256",p.barrierEventSha256(),"checkedAt",p.checkedAt());
        }else if(proof instanceof EffectReceiptProof p){
            observation(p.effectId(),p.resourceSha256());Receipt r=decodeReceipt(p.receipt());if(!"command.execution.effect.receipt".equals(r.messageType())||!Set.of("COMMITTED","SETTLED","DESCRIBED","READ","STAGED").contains(r.decision()))throw invalid();
            if(r.result() instanceof CommittedResult c){if(!c.effectId().equals(p.effectId())||!c.resourceSha256().equals(p.resourceSha256()))throw invalid();}else if(r.result() instanceof SettledResult c){if(!c.effectId().equals(p.effectId())||!c.resourceSha256().equals(p.resourceSha256()))throw invalid();}else if(!(r.result() instanceof DescribedResult||r.result() instanceof ReadResult||r.result() instanceof StagedResult))throw invalid();
            put(n,"kind","EFFECT_RECEIPT","effectId",p.effectId(),"resourceSha256",p.resourceSha256(),"receiptBytesBase64",b64(p.receipt()),"receiptSha256",hex(sha256(p.receipt())));
        }else if(proof instanceof EffectSuccessReference p){
            uuid(p.runtimeDispatchId());uuid(p.runToken());effect(p.effectId());uuid(p.originalRequestId());hash(p.resourceSha256());hash(p.originalRequestSha256());hash(p.originalResponseSha256());hash(p.executionGrantSha256());successPhase(p.effectKind(),p.phase(),p.decision());
            put(n,"kind","EFFECT_SUCCESS_REFERENCE","runtimeDispatchId",p.runtimeDispatchId(),"runToken",p.runToken(),"effectId",p.effectId(),"effectKind",p.effectKind().name(),"phase",p.phase().name(),"decision",p.decision(),"resourceSha256",p.resourceSha256(),"originalRequestId",p.originalRequestId(),"originalRequestSha256",p.originalRequestSha256(),"originalResponseSha256",p.originalResponseSha256(),"executionGrantSha256",p.executionGrantSha256());
        }else if(proof instanceof ExecutionEffectsProof p){uuid(p.runtimeDispatchId());uuid(p.runToken());put(n,"kind","EXECUTION_EFFECTS","runtimeDispatchId",p.runtimeDispatchId(),"runToken",p.runToken());proofEffects(n,p.effects(),p.runtimeDispatchId(),false);}
        else if(proof instanceof SettlementProof p){
            uuid(p.runtimeDispatchId());uuid(p.runToken());var ack=AgentRuntimeV2ExecutionCodec.decodeAck(p.terminalAck());var receipt=AgentRuntimeV2WireCodec.decodeReceipt(p.settlementReceipt());
            if(!Set.of("SUCCEEDED","FAILED").contains(ack.ackStatus())||receipt.leaseFactId()==null||!ack.runtimeDispatchId().equals(p.runtimeDispatchId())||!ack.runtimeDispatchId().equals(receipt.runtimeDispatchId())||!ack.ackMessageId().equals(receipt.ackMessageId())||!ack.sourceMessageId().equals(receipt.sourceMessageId())||!ack.ackStatus().equals(receipt.ackStatus())||ack.ackAt()!=receipt.ackAt())throw invalid();
            put(n,"kind",p instanceof ExecutionSettlementProof?"EXECUTION_SETTLEMENT":"EXECUTION_SETTLEMENT_GRAPH","runtimeDispatchId",p.runtimeDispatchId(),"runToken",p.runToken(),"terminalAckBytesBase64",b64(p.terminalAck()),"terminalAckSha256",hex(sha256(p.terminalAck())),"settlementReceiptBytesBase64",b64(p.settlementReceipt()),"settlementReceiptSha256",hex(sha256(p.settlementReceipt())));
            if(p instanceof ExecutionSettlementProof inline)proofEffects(n,inline.effects(),p.runtimeDispatchId(),true);
            else {var graph=(ExecutionSettlementGraphProof)p;nonnegative(graph.effectCount());if(graph.effectCount()>4096)throw invalid();hash(graph.effectGraphSha256());put(n,"effectCount",graph.effectCount(),"effectGraphSha256",graph.effectGraphSha256());}
        }else throw invalid();return encoded(n,65536);
    }
    private static void proofEffects(ObjectNode n,List<EffectReceiptProof> effects,String dispatch,boolean settled){
        if(effects.size()>128||!settled&&effects.isEmpty())throw invalid();Set<String> ids=new HashSet<>();boolean committed=false;ArrayNode out=n.putArray("effects");for(EffectReceiptProof effect:effects){
            byte[] raw=encodeProof(effect);Receipt receipt=decodeReceipt(effect.receipt());if(!ids.add(effect.effectId())||!receipt.runtimeDispatchId().equals(dispatch))throw invalid();
            if("COMMITTED".equals(receipt.decision())||receipt.result() instanceof SettledResult r&&"COMMITTED_PROVEN".equals(r.disposition()))committed=true;out.add(parse(raw,65536));
        }if(!settled&&!committed)throw invalid();
    }
    private static JsonNode renewalResponse(byte[] raw){
        JsonNode n=parse(raw,32768);String type=string(n,"messageType");if("command.execution.grant".equals(type))AgentRuntimeV2WireCodec.decodeExecutionGrant(raw);
        else if("command.execution.renew.rejected".equals(type))canonical(raw,AgentRuntimeV2ExecutionCodec.rejected("RENEWAL",string(n,"correlationId"),string(n,"runtimeDispatchId"),string(n,"startedReceiptId"),string(n,"reasonCode")));
        else throw invalid();return n;
    }
    public static Proof decodeProof(byte[] raw){
        JsonNode n=parse(raw,65536);Proof p=switch(string(n,"kind")){
            case "RENEWAL_MAPPING" -> new RenewalMapping(string(n,"requestId"),string(n,"requestSha256"),string(n,"runtimeDispatchId"),string(n,"startedReceiptId"),string(n,"startedReceiptSha256"),string(n,"previousExecutionGrantId"),string(n,"previousExecutionGrantSha256"),string(n,"mappingEventId"),string(n,"mappingEventSha256"),pair(n,"responseBytesBase64","responseSha256",32768,false));
            case "RETIREMENT_BARRIER" -> new RetirementBarrier(string(n,"requestId"),string(n,"requestSha256"),string(n,"runtimeDispatchId"),string(n,"retiredHolderGenerationFactId"),string(n,"retiredHolderGenerationSnapshotSha256"),string(n,"currentHolderGenerationFactId"),string(n,"currentHolderGenerationSnapshotSha256"),string(n,"barrierEventId"),string(n,"barrierEventSha256"),decimal(n,"checkedAt",true));
            case "EXECUTION_ADMISSION_BARRIER" -> new ExecutionAdmissionBarrier(string(n,"runtimeDispatchId"),string(n,"startedReceiptId"),string(n,"startedReceiptSha256"),string(n,"originEventId"),string(n,"originEventSha256"),string(n,"barrierEventId"),string(n,"barrierEventSha256"),decimal(n,"checkedAt",true));
            case "EFFECT_RECEIPT" -> new EffectReceiptProof(string(n,"effectId"),string(n,"resourceSha256"),pair(n,"receiptBytesBase64","receiptSha256",65536,false));
            case "EFFECT_SUCCESS_REFERENCE" -> new EffectSuccessReference(string(n,"runtimeDispatchId"),string(n,"runToken"),string(n,"effectId"),enumValue(Kind.class,string(n,"effectKind")),enumValue(Phase.class,string(n,"phase")),string(n,"decision"),string(n,"resourceSha256"),string(n,"originalRequestId"),string(n,"originalRequestSha256"),string(n,"originalResponseSha256"),string(n,"executionGrantSha256"));
            case "EXECUTION_EFFECTS" -> new ExecutionEffectsProof(string(n,"runtimeDispatchId"),string(n,"runToken"),decodeProofEffects(n));
            case "EXECUTION_SETTLEMENT" -> new ExecutionSettlementProof(string(n,"runtimeDispatchId"),string(n,"runToken"),pair(n,"terminalAckBytesBase64","terminalAckSha256",599,false),pair(n,"settlementReceiptBytesBase64","settlementReceiptSha256",32768,false),decodeProofEffects(n));
            case "EXECUTION_SETTLEMENT_GRAPH" -> new ExecutionSettlementGraphProof(string(n,"runtimeDispatchId"),string(n,"runToken"),pair(n,"terminalAckBytesBase64","terminalAckSha256",599,false),pair(n,"settlementReceiptBytesBase64","settlementReceiptSha256",32768,false),decimal(n,"effectCount",false),string(n,"effectGraphSha256"));
            default -> throw invalid();
        };canonical(raw,encodeProof(p));return p;
    }
    private static List<EffectReceiptProof> decodeProofEffects(JsonNode n){List<EffectReceiptProof> values=new ArrayList<>();for(JsonNode item:array(n,"effects",128)){if(!"EFFECT_RECEIPT".equals(string(item,"kind")))throw invalid();values.add(new EffectReceiptProof(string(item,"effectId"),string(item,"resourceSha256"),pair(item,"receiptBytesBase64","receiptSha256",65536,false)));}return values;}
    public static byte[] encodeReconciliation(Reconciliation r){
        if(r==null||r.subjectKind()==null)throw invalid();if(Set.of(Subject.EFFECT,Subject.EFFECT_SUCCESS).contains(r.subjectKind()))effect(r.subjectId());else uuid(r.subjectId());nullableHash(r.originalRequestSha256());
        byte[] proof=r.proof()==null?null:encodeProof(r.proof());if("UNKNOWN".equals(r.disposition())){if(proof!=null)throw invalid();}
        else{
            if(proof==null)throw invalid();if(r.subjectKind()==Subject.RENEWAL){
                in(r.disposition(),"GRANTED_HISTORICAL","DENIED_HISTORICAL","NOT_COMMITTED");String requestId,requestSha;
                if(r.proof() instanceof RetirementBarrier p){if(!"NOT_COMMITTED".equals(r.disposition()))throw invalid();requestId=p.requestId();requestSha=p.requestSha256();}
                else if(r.proof() instanceof RenewalMapping p){if("NOT_COMMITTED".equals(r.disposition())||"command.execution.grant".equals(string(renewalResponse(p.response()),"messageType"))!= "GRANTED_HISTORICAL".equals(r.disposition()))throw invalid();requestId=p.requestId();requestSha=p.requestSha256();}
                else throw invalid();if(!requestId.equals(r.subjectId())||!requestSha.equals(r.originalRequestSha256()))throw invalid();
            }else if(r.subjectKind()==Subject.EFFECT_SUCCESS){if(!"SUCCESS_REFERENCE".equals(r.disposition())||!(r.proof() instanceof EffectSuccessReference p)||!p.effectId().equals(r.subjectId()))throw invalid();}
            else{
                in(r.disposition(),"NOT_RUN_PROVEN","COMMITTED_PROVEN","SETTLED_KNOWN");if(r.subjectKind()==Subject.EXECUTION){
                    String dispatch=proofDispatch(r.proof());if(!r.subjectId().equals(dispatch)||"NOT_RUN_PROVEN".equals(r.disposition())&&!(r.proof() instanceof ExecutionAdmissionBarrier)||"COMMITTED_PROVEN".equals(r.disposition())&&!(r.proof() instanceof ExecutionEffectsProof)||"SETTLED_KNOWN".equals(r.disposition())&&!(r.proof() instanceof SettlementProof))throw invalid();
                }else if(!("NOT_RUN_PROVEN".equals(r.disposition())&&r.proof() instanceof ExecutionAdmissionBarrier)){
                    if(!(r.proof() instanceof EffectReceiptProof p)||!p.effectId().equals(r.subjectId()))throw invalid();Receipt receipt=decodeReceipt(p.receipt());
                    String disposition=receipt.result() instanceof SettledResult s?s.disposition():null;
                    if("COMMITTED_PROVEN".equals(r.disposition())&&!("COMMITTED".equals(receipt.decision())||"COMMITTED_PROVEN".equals(disposition))||"SETTLED_KNOWN".equals(r.disposition())&&!"SETTLED".equals(receipt.decision())||"NOT_RUN_PROVEN".equals(r.disposition())&&!"NOT_RUN_PROVEN".equals(disposition))throw invalid();
                }
            }
        }
        ObjectNode n=JSON.createObjectNode();put(n,"subjectKind",r.subjectKind().name(),"subjectId",r.subjectId(),"disposition",r.disposition(),"originalRequestSha256",r.originalRequestSha256(),"proofBytesBase64",proof==null?null:b64(proof),"proofSha256",proof==null?null:hex(sha256(proof)));return encoded(n,98304);
    }
    public static Reconciliation decodeReconciliation(byte[] raw){JsonNode n=parse(raw,98304);byte[] proof=pair(n,"proofBytesBase64","proofSha256",65536,true);var r=new Reconciliation(enumValue(Subject.class,string(n,"subjectKind")),string(n,"subjectId"),string(n,"disposition"),nullableString(n,"originalRequestSha256"),proof==null?null:decodeProof(proof));canonical(raw,encodeReconciliation(r));return r;}
    private static String proofDispatch(Proof p){if(p instanceof RenewalMapping v)return v.runtimeDispatchId();if(p instanceof RetirementBarrier v)return v.runtimeDispatchId();if(p instanceof ExecutionAdmissionBarrier v)return v.runtimeDispatchId();if(p instanceof ExecutionEffectsProof v)return v.runtimeDispatchId();if(p instanceof SettlementProof v)return v.runtimeDispatchId();if(p instanceof EffectSuccessReference v)return v.runtimeDispatchId();if(p instanceof EffectReceiptProof v)return decodeReceipt(v.receipt()).runtimeDispatchId();return null;}
    private static void successPhase(Kind kind,Phase phase,String decision){
        if(kind==null||phase==null||!PHASES.get(kind).contains(phase))throw invalid();boolean valid=switch(decision){case "DESCRIBED"->phase==Phase.DESCRIBE;case "READ"->phase==Phase.READ;case "STAGED"->kind==Kind.FILE_STAGE&&phase==Phase.STAGE;case "COMMITTED"->kind==Kind.FILE_COMMIT&&Set.of(Phase.COMMIT,Phase.OBSERVE).contains(phase);case "SETTLED"->Set.of(Phase.SETTLE,Phase.OBSERVE).contains(phase);case "OBSERVED"->phase==Phase.OBSERVE&&Set.of(Kind.SKILL_ACTIVATE,Kind.WORKSPACE_PREPARE,Kind.WORKSPACE_MUTATE,Kind.PROCESS_START,Kind.PROCESS_EXIT).contains(kind);default->false;};if(!valid)throw invalid();
    }
    private static void validateLocalContent(EffectRequest request,byte[] content){
        if(request.phase()!=Phase.OBSERVE||content.length>CHUNK_CAP)throw invalid();var observation=decodeEvidence(content);String resource=hex(sha256(encodeResource(request.effectKind(),request.phase(),request.resource())));
        if(request.effectKind()==Kind.SKILL_ACTIVATE){if(!(observation instanceof LocalCommitObservation o)||!o.effectId().equals(request.effectId())||!o.resourceSha256().equals(resource)||!o.runToken().equals(request.runToken()))throw invalid();}
        else if(Set.of(Kind.WORKSPACE_PREPARE,Kind.WORKSPACE_MUTATE).contains(request.effectKind())){
            if(!(observation instanceof WorkspaceTreeObservation o)||!o.effectId().equals(request.effectId())||!o.resourceSha256().equals(resource)||!o.runToken().equals(request.runToken()))throw invalid();String key,base;if(request.resource() instanceof WorkspacePrepare r){key=r.workspaceKey();base=r.baseTreeSha256();}else{var r=(WorkspaceMutate)request.resource();key=r.workspaceKey();base=r.baseTreeSha256();}if(!o.workspaceKey().equals(key)||!o.baseTreeSha256().equals(base))throw invalid();
        }else if(request.effectKind()==Kind.PROCESS_EXIT){
            if(!(observation instanceof ProcessDrainObservation o)||!o.runToken().equals(request.runToken()))throw invalid();var r=(ProcessExit)request.resource();var proof=o.proof();if(proof.processes().stream().noneMatch(p->p.pid()==r.pid()&&p.startTime()==r.startTime())||proof.directClose()!=null&&(!Objects.equals(proof.directClose().code(),r.exitCode())||!Objects.equals(proof.directClose().signal(),r.signal())))throw invalid();
        }else throw invalid();
    }
    /** Pure comparable commitment. Callers independently authenticate every
     * original producer/server pair and COMPLETE membership; a hash is not ACL. */
    public static String completeGraphSha256(String dispatch,String run,String dispatchSha,String startedSha,String startFact,List<EffectSuccessReference> members){
        uuid(dispatch);uuid(run);hash(dispatchSha);hash(startedSha);uuid(startFact);if(members==null||members.size()>4096)throw invalid();var ordered=new TreeMap<String,EffectSuccessReference>();for(var member:members){encodeProof(member);if(!dispatch.equals(member.runtimeDispatchId())||!run.equals(member.runToken())||ordered.put(member.effectId(),member)!=null)throw invalid();}
        var out=new java.io.ByteArrayOutputStream();for(String value:List.of("agent-runtime-v2-complete-effect-graph-v1",dispatch,run,dispatchSha,startedSha,startFact,Integer.toString(members.size())))graphComponent(out,value);
        for(var member:ordered.values())for(String value:List.of(member.effectId(),member.effectKind().name(),member.phase().name(),member.resourceSha256(),member.originalRequestId(),member.originalRequestSha256(),member.originalResponseSha256(),member.executionGrantSha256()))graphComponent(out,value);return hex(sha256(out.toByteArray()));
    }
    private static void graphComponent(java.io.ByteArrayOutputStream out,String value){byte[] raw=value.getBytes(StandardCharsets.UTF_8);out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(raw.length).array());out.writeBytes(raw);}
    public static byte[] encodeReceipt(Receipt r){
        in(r.messageType(),"command.execution.control.receipt","command.execution.effect.receipt","command.execution.reconcile.receipt");uuid(r.requestId());uuid(r.runtimeDispatchId());uuid(r.receiptId());hash(r.requestSha256());positive(r.serverAt());byte[] result=null;
        if(r.result()==null)in(r.decision(),"DENIED","CONFLICT","UNAVAILABLE");
        else if("command.execution.control.receipt".equals(r.messageType())){if(!(r.result() instanceof Control c)||!c.access().name().equals(r.decision()))throw invalid();result=encodeControl(c);}
        else if("command.execution.effect.receipt".equals(r.messageType()))result=encodeEffectResult(r.decision(),r.result());
        else if("command.execution.reconcile.receipt".equals(r.messageType())&&r.result() instanceof Reconciliation c){if(!c.disposition().equals(r.decision())||c.subjectKind()==Subject.EXECUTION&&!c.subjectId().equals(r.runtimeDispatchId())||c.proof()!=null&&!r.runtimeDispatchId().equals(proofDispatch(c.proof())))throw invalid();result=encodeReconciliation(c);}
        else throw invalid();
        ObjectNode n=JSON.createObjectNode();n.put("schemaVersion",2);put(n,"messageType",r.messageType(),"requestId",r.requestId(),"runtimeDispatchId",r.runtimeDispatchId(),"receiptId",r.receiptId(),"requestSha256",r.requestSha256(),"decision",r.decision(),"serverAt",r.serverAt(),"resultBytesBase64",result==null?null:b64(result),"resultSha256",result==null?null:hex(sha256(result)));return encoded(n,FRAME_CAP);
    }
    public static Receipt decodeReceipt(byte[] raw){
        JsonNode n=parse(raw,FRAME_CAP);if(!n.path("schemaVersion").isInt()||n.path("schemaVersion").intValue()!=2)throw invalid();String type=string(n,"messageType"),decision=string(n,"decision");byte[] result=pair(n,"resultBytesBase64","resultSha256",98304,true);Result decoded=null;
        if(result!=null){if(type.equals("command.execution.control.receipt"))decoded=decodeControl(result);else if(type.equals("command.execution.effect.receipt"))decoded=decodeEffectResult(decision,result);else if(type.equals("command.execution.reconcile.receipt"))decoded=decodeReconciliation(result);else throw invalid();}
        var value=new Receipt(type,string(n,"requestId"),string(n,"runtimeDispatchId"),string(n,"receiptId"),string(n,"requestSha256"),decision,decimal(n,"serverAt",true),decoded);canonical(raw,encodeReceipt(value));return value;
    }
    public static String effectId(String stableKey,String semanticFingerprint,String dispatch,String originalStart,String runToken,Kind kind,Phase phase,Resource resource){
        if(stableKey==null||stableKey.isEmpty()||stableKey.getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();hash(semanticFingerprint);uuid(dispatch);uuid(originalStart);uuid(runToken);byte[] raw=encodeResource(kind,phase,resource);String digest=null;
        if(resource instanceof SkillIdentity s)digest=s.packageDigest();else if(resource instanceof SkillRead s)digest=s.identity().packageDigest();else if(resource instanceof FileStage s)digest=s.contentSha256();else if(resource instanceof FileCommit s)digest=s.manifestSha256();else if(resource instanceof WorkspaceMutate s)digest=s.manifestSha256();else if(resource instanceof WorkspacePrepare s)digest=s.baseTreeSha256();
        var out=new java.io.ByteArrayOutputStream();for(String field:Arrays.asList("agent-runtime-v2-effect-v1",stableKey,semanticFingerprint,dispatch,originalStart,runToken,kind.name(),new String(raw,StandardCharsets.UTF_8),digest)){byte[] content=field==null?null:field.getBytes(StandardCharsets.UTF_8);out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(content==null?-1:content.length).array());if(content!=null)out.writeBytes(content);}return "eff_"+hex(sha256(out.toByteArray()));
    }
    private static void skill(ObjectNode n,SkillIdentity r){if(r==null)throw invalid();exact(r.installationId());exact(r.productVersionId());if(r.packageDigest()==null||!r.packageDigest().matches("sha256:[0-9a-f]{64}"))throw invalid();exact(r.skillKey());exact(r.skillVersion());if(!r.skillKey().matches("[a-z0-9]+(?:-[a-z0-9]+)*")||!r.skillVersion().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw invalid();put(n,"installationId",r.installationId(),"productVersionId",r.productVersionId(),"packageDigest",r.packageDigest(),"skillKey",r.skillKey(),"skillVersion",r.skillVersion());}
    private static SkillIdentity skill(JsonNode n){return new SkillIdentity(string(n,"installationId"),string(n,"productVersionId"),string(n,"packageDigest"),string(n,"skillKey"),string(n,"skillVersion"));}
    private static void task(ObjectNode n,String task,String item,boolean optional){exact(task);if(item!=null)exact(item);else if(!optional)throw invalid();put(n,"taskId",task,"workItemId",item);}
    private static void file(ObjectNode n,String execution,String task,String item,String run){exact(execution);n.put("executionId",execution);task(n,task,item,false);exact(run);n.put("runId",run);}
    private static void grant(ObjectNode n,GrantReference r){if(r==null)throw invalid();uuid(r.requestId());uuid(r.runtimeDispatchId());uuid(r.startedReceiptId());hash(r.startedReceiptSha256());uuid(r.executionGrantId());hash(r.executionGrantSha256());put(n,"requestId",r.requestId(),"runtimeDispatchId",r.runtimeDispatchId(),"startedReceiptId",r.startedReceiptId(),"startedReceiptSha256",r.startedReceiptSha256(),"executionGrantId",r.executionGrantId(),"executionGrantSha256",r.executionGrantSha256());}
    private static GrantReference grant(JsonNode n){return new GrantReference(string(n,"requestId"),string(n,"runtimeDispatchId"),string(n,"startedReceiptId"),string(n,"startedReceiptSha256"),string(n,"executionGrantId"),string(n,"executionGrantSha256"));}
    private static void chunk(long offset,long length){nonnegative(offset);nonnegative(length);if(length>CHUNK_CAP)throw invalid();}
    private static void safeFileId(String v){if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw invalid();}
    private static void safeFileLength(long v){if(v<0||v>9007199254740991L)throw invalid();}
    private static void relativePath(String v){if(v==null||v.length()>512||!v.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,127})*"))throw invalid();}
    private static void mediaType(String v){if(v==null||!v.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}"))throw invalid();}
    private static void addManifestPath(Set<String> paths,String path){if(paths.stream().anyMatch(p->p.equals(path)||p.startsWith(path+"/")||path.startsWith(p+"/")))throw invalid();paths.add(path);}
    private static String processIdentity(long pid,long start,String ns){return pid+":"+start+":"+ns;}
    private static void namespace(String v){if(v==null||!v.matches("pid:\\[[1-9][0-9]*\\]"))throw invalid();}
    private static void observation(String id,String digest){effect(id);hash(digest);}
    private static void close(ObjectNode n,String key,CloseProjection c){if(c==null){n.putNull(key);return;}if(c.signal()!=null)in(c.signal(),SIGNALS.toArray(String[]::new));nonnegative(c.observedAt());ObjectNode o=n.putObject(key);if(c.code()==null)o.putNull("code");else o.put("code",c.code());put(o,"signal",c.signal(),"observedAt",c.observedAt());}
    private static CloseProjection close(JsonNode n,String key){JsonNode v=n.get(key);if(v==null)throw invalid();return v.isNull()?null:new CloseProjection(nullableInteger(v,"code"),nullableString(v,"signal"),decimal(v,"observedAt",false));}
    private static JsonNode array(JsonNode n,String key,int cap){JsonNode v=n.get(key);if(v==null||!v.isArray()||v.size()>cap)throw invalid();return v;}
    private static boolean booleanValue(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isBoolean())throw invalid();return v.booleanValue();}
    private static int requiredInteger(JsonNode n,String key){Integer v=nullableInteger(n,key);if(v==null)throw invalid();return v;}
    private static long safeNumber(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw invalid();long number=v.longValue();safeFileLength(number);return number;}
    private static void exact(String v){RuntimeV2ExactIdentity.require(v,100,"R4 exact identity");}
    // Fixed ASCII grammar, checked in full on every call. No retained decoded
    // wire, authority or business result; avoid compiling the same regex for
    // every member in each independent entry/finish/beforeCommit validation.
    private static boolean lowerHex(char value){return value>='0'&&value<='9'||value>='a'&&value<='f';}
    private static void uuid(String v){
        if(v==null||v.length()!=36||v.charAt(14)!='4'||"89ab".indexOf(v.charAt(19))<0)throw invalid();
        for(int i=0;i<36;i++)if(i==8||i==13||i==18||i==23){if(v.charAt(i)!='-')throw invalid();}else if(!lowerHex(v.charAt(i)))throw invalid();
    }
    private static void hash(String v){if(v==null||v.length()!=64)throw invalid();for(int i=0;i<64;i++)if(!lowerHex(v.charAt(i)))throw invalid();}
    private static void nullableHash(String v){if(v!=null)hash(v);}
    private static void effect(String v){if(v==null||v.length()!=68||!v.startsWith("eff_"))throw invalid();for(int i=4;i<68;i++)if(!lowerHex(v.charAt(i)))throw invalid();}
    private static void engine(String v){if(!"legacy-codex".equals(v))throw invalid();}
    private static void nonnegative(long v){if(v<0)throw invalid();}
    private static void positive(long v){if(v<=0)throw invalid();}
    private static void in(String value,String... values){if(!Arrays.asList(values).contains(value))throw invalid();}
    private static <T extends Enum<T>>T enumValue(Class<T> type,String value){try{return Enum.valueOf(type,value);}catch(RuntimeException failure){throw invalid();}}
    private static JsonNode parse(byte[] raw,int cap){if(raw==null||raw.length==0||raw.length>cap||raw[0]!='{')throw invalid();try{String text=new String(raw,StandardCharsets.UTF_8);if(!Arrays.equals(raw,text.getBytes(StandardCharsets.UTF_8)))throw invalid();try(var parser=JSON.createParser(raw)){JsonNode n=JSON.readTree(parser);if(n==null||!n.isObject()||parser.nextToken()!=null)throw invalid();return n;}}catch(tools.jackson.core.JacksonException failure){throw invalid();}}
    private static String string(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isTextual())throw invalid();return v.textValue();}
    private static String nullableString(JsonNode n,String key){JsonNode v=n.get(key);if(v==null)throw invalid();return v.isNull()?null:string(n,key);}
    private static long decimal(JsonNode n,String key,boolean positive){
        String v=string(n,key);int length=v.length();if(length==0||length>19||length>1&&v.charAt(0)=='0'||positive&&v.charAt(0)=='0')throw invalid();
        long value=0;for(int i=0;i<length;i++){char c=v.charAt(i);if(c<'0'||c>'9')throw invalid();int digit=c-'0';if(value>(Long.MAX_VALUE-digit)/10)throw invalid();value=value*10+digit;}return value;
    }
    private static Long nullableDecimal(JsonNode n,String key){return nullableString(n,key)==null?null:decimal(n,key,true);}
    private static Integer nullableInteger(JsonNode n,String key){JsonNode v=n.get(key);if(v==null)throw invalid();if(v.isNull())return null;if(!v.isIntegralNumber()||!v.canConvertToInt())throw invalid();return v.intValue();}
    private static byte[] base64Nullable(JsonNode n,String key,int cap,boolean empty){String v=nullableString(n,key);return v==null?null:base64(v,cap,empty);}
    private static byte[] base64(String v,int cap,boolean empty){if(v==null||v.length()>((cap+2L)/3)*4)throw invalid();try{byte[] b=Base64.getDecoder().decode(v);if((b.length==0&&!empty)||b.length>cap||!b64(b).equals(v))throw invalid();return b;}catch(IllegalArgumentException failure){throw invalid();}}
    private static byte[] pair(JsonNode n,String encoded,String digest,int cap,boolean optional){String a=nullableString(n,encoded),b=nullableString(n,digest);if(optional&&a==null&&b==null)return null;hash(b);byte[] raw=base64(a,cap,false);if(!hex(sha256(raw)).equals(b))throw invalid();return raw;}
    private static String b64(byte[] raw){return Base64.getEncoder().encodeToString(raw);}
    // Only this fixed schema encoder uses JsonNode. No caller-supplied tree/map
    // reaches a transaction or is returned as a permissive public wire object.
    private static void put(ObjectNode out,Object... fields){if(fields.length%2!=0)throw invalid();for(int i=0;i<fields.length;i+=2){String key=(String)fields[i];Object v=fields[i+1];if(v==null)out.putNull(key);else if(v instanceof String s)out.put(key,s);else if(v instanceof Long l)out.put(key,Long.toString(l));else throw invalid();}}
    private static byte[] encoded(ObjectNode n,int cap){
        try{
            var output=new ByteArrayOutputStream();
            // The existing schema creates only these fixed JSON node types.
            // Use the same Jackson UTF-8 generator/escaping without resolving
            // a Databind serializer for the already-typed tree on every call.
            try(var generator=JSON.createGenerator(output)){writeEncoded(generator,n);}
            byte[] raw=output.toByteArray();if(raw.length==0||raw.length>cap)throw invalid();return raw;
        }catch(tools.jackson.core.JacksonException failure){throw invalid();}
    }
    private static void writeEncoded(JsonGenerator generator,JsonNode value){
        if(value.isObject()){
            generator.writeStartObject();
            for(var entry:value.properties()){generator.writeName(entry.getKey());writeEncoded(generator,entry.getValue());}
            generator.writeEndObject();
        }else if(value.isArray()){
            generator.writeStartArray();for(JsonNode item:value)writeEncoded(generator,item);generator.writeEndArray();
        }else if(value.isTextual())generator.writeString(value.textValue());
        else if(value.isIntegralNumber()&&value.canConvertToLong())generator.writeNumber(value.longValue());
        else if(value.isBoolean())generator.writeBoolean(value.booleanValue());
        else if(value.isNull())generator.writeNull();
        else throw invalid();
    }
    private static void canonical(byte[] raw,byte[] expected){if(!Arrays.equals(raw,expected))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("noncanonical runtime-v2 R4 wire");}
}
