package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.common.AgentMessageWire;
import cn.jia.core.entity.ProtocolMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

/** Public DTOs only. Durable commands and their identity/hash preimages remain unchanged. */
public final class WorkspaceExecutionWire {
    private WorkspaceExecutionWire() { }
    private static String decimal(long v, boolean positive) {
        if (v < (positive ? 1 : 0)) throw new IllegalArgumentException("Invalid workspace integer");
        return Long.toString(v);
    }
    public record Input(String inputRef,String fileId,String version,String originalFilename,
            String contentMimeType,String byteLength,String sha256) { }
    public static Input from(PersonalWorkspaceExecutionService.RuntimeInput v) {
        return new Input(v.inputRef(),v.fileId(),decimal(v.version(),true),v.originalFilename(),v.contentMimeType(),decimal(v.byteLength(),false),v.sha256());
    }
    public record InputCommand(String inputId,String relativePath,String downloadPath,String length,String sha256) { }
    public record Output(String outputId,String relativePath,String contentType,String maxLength,String uploadPath) { }
    public record Command(String taskId,String runId,List<InputCommand> inputManifest,List<Output> outputManifest) { }
    public static Command from(PersonalWorkspaceExecutionService.RuntimeCommand v) {
        if(v==null)return null;
        return new Command(v.taskId(),v.runId(),v.inputManifest().stream().map(i->new InputCommand(i.inputId(),i.relativePath(),i.downloadPath(),decimal(i.length(),false),i.sha256())).toList(),
                v.outputManifest().stream().map(o->new Output(o.outputId(),o.relativePath(),o.contentType(),decimal(o.maxLength(),true),o.uploadPath())).toList());
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Execution(String executionId,String taskId,String runId,String conversationId,String targetAgentId,
            String state,String failureCode,String failureMessage,String grantRevision,String outputContentMimeType,
            List<Input> inputs,Command runtimeCommand,String executionMode,String businessTaskId,String workItemId,String workItemState) { }
    public static Execution from(PersonalWorkspaceExecutionService.ExecutionView v) {
        if(v==null)return null;
        return new Execution(v.executionId(),v.taskId(),v.runId(),v.conversationId(),v.targetAgentId(),v.state(),v.failureCode(),v.failureMessage(),decimal(v.grantRevision(),true),v.outputContentMimeType(),v.inputs().stream().map(WorkspaceExecutionWire::from).toList(),from(v.runtimeCommand()),v.executionMode(),v.businessTaskId(),v.workItemId(),v.workItemState());
    }
    public static ProtocolMessage<Map<String,Object>> from(PersonalWorkspaceExecutionService.RuntimeQueuedCommand v) {
        var data=new LinkedHashMap<String,Object>();
        data.put("schemaVersion",v.schemaVersion());data.put("messageId",v.messageId());data.put("commandId",v.commandId());
        data.put("tenantId",v.tenantId());data.put("clientId",v.clientId());data.put("ownerJiacn",v.ownerJiacn());
        data.put("taskId",v.taskId());data.put("runId",v.runId());data.put("targetAgentId",v.targetAgentId());
        data.put("commandType",v.commandType());data.put("instruction",v.instruction());data.put("payload",from(v.payload()));
        return AgentMessageWire.event(v.messageType(),data,null);
    }
}
