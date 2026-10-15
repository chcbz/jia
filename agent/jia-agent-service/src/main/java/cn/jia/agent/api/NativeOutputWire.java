package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import java.util.List;

/** Explicit native file boundary; stored output/manifest facts remain unchanged. */
public final class NativeOutputWire {
    private NativeOutputWire() { }
    static long byteLength(String value) {
        if (value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid byteLength");
        return Long.parseLong(value);
    }
    public record CommitItemResponse(String outputId,String fileId,String fileVersion,String sha256,
            String contentMimeType,String byteLength) {
        static CommitItemResponse from(PersonalWorkspaceExecutionService.CommitItem value) {
            return new CommitItemResponse(value.outputId(),value.fileId(),value.fileVersion()==null?null:Integer.toString(value.fileVersion()),
                    value.sha256(),value.contentMimeType(),Long.toString(value.byteLength()));
        }
    }
    public record CommitResponse(String manifestId,String state,List<CommitItemResponse> items) {
        static CommitResponse from(PersonalWorkspaceExecutionService.CommitView value) {
            return new CommitResponse(value.manifestId(),value.state(),value.items().stream().map(CommitItemResponse::from).toList());
        }
    }
    public record StagedOutputResponse(String outputId,String sha256,String byteLength,String state) {
        static StagedOutputResponse from(PersonalWorkspaceExecutionService.StagedOutput value) {
            return new StagedOutputResponse(value.outputId(),value.sha256(),Long.toString(value.byteLength()),value.state());
        }
    }
}
