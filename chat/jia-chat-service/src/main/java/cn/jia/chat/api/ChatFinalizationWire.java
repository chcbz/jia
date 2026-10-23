package cn.jia.chat.api;

import cn.jia.chat.service.ChatSelectedOutputFinalizationService;
import java.util.*;

/** Public finalization DTO, not the immutable request digest or persisted state machine. */
public final class ChatFinalizationWire {
    private ChatFinalizationWire() {}
    public static Map<String,Object> receipt(ChatSelectedOutputFinalizationService.Receipt r) {
        var out=new LinkedHashMap<String,Object>();
        out.put("operationId",r.operationId());out.put("taskId",r.taskId());out.put("conversationId",r.conversationId());
        String state=switch(r.state()){case "pending"->"PENDING";case "completed"->"COMPLETED";case "failed"->"FAILED";default->throw invalid();};
        out.put("state",state);out.put("stateVersion",decimal(r.stateVersion(),true));
        if(!Set.of("PROMOTING","READY_TO_SUBMIT","SUBMITTED","ACCEPTING","TASK_COMPLETED").contains(r.stage()))throw invalid();
        out.put("stage",r.stage());out.put("expectedTaskVersion",decimal(r.expectedTaskVersion(),false));
        out.put("expectedAssignmentRevision",decimal(r.expectedAssignmentRevision(),false));
        out.put("selectedOutputs",r.selectedOutputs().stream().map(ChatFinalizationWire::selection).toList());
        String delivery=r.deliveryState()==null?null:switch(r.deliveryState()){
            case "submitted"->"SUBMITTED";case "accepted"->"ACCEPTED";case "changes_requested"->"CHANGES_REQUESTED";default->throw invalid();};
        String task=switch(r.taskState()){
            case "open"->"OPEN";case "planning"->"PLANNING";case "assigned"->"ASSIGNED";case "running"->"RUNNING";
            case "reviewing"->"REVIEWING";case "blocked"->"BLOCKED";case "completed"->"COMPLETED";case "failed"->"FAILED";
            case "cancelled"->"CANCELLED";case "archived"->"ARCHIVED";default->throw invalid();};
        out.put("deliveryId",r.deliveryId());out.put("deliveryState",delivery);out.put("taskState",task);
        out.put("taskVersion",decimal(r.taskVersion(),false));out.put("errorCode",r.errorCode());out.put("retryable",r.retryable());
        boolean historical="TASK_COMPLETED".equals(r.stage());
        if(historical && (!"COMPLETED".equals(state)||!"ACCEPTED".equals(delivery)||!"COMPLETED".equals(task)))throw invalid();
        if("COMPLETED".equals(state)&&(!Set.of("SUBMITTED","TASK_COMPLETED").contains(r.stage())
                ||r.deliveryId()==null||delivery==null||r.errorCode()!=null||r.retryable()))throw invalid();
        if("FAILED".equals(state)&&(r.errorCode()==null||!ERRORS.contains(r.errorCode())))throw invalid();
        return Collections.unmodifiableMap(out);
    }
    private static final Set<String> ERRORS=Set.of("FINALIZATION_BAD_REQUEST","FINALIZATION_NOT_FOUND","FINALIZATION_SOURCE_UNAVAILABLE",
            "FINALIZATION_CONFLICT","FINALIZATION_GRANT_CHANGED","FINALIZATION_DELIVERY_CHANGES_REQUESTED","FINALIZATION_IDEMPOTENCY_CONFLICT",
            "FINALIZATION_STORAGE_UNAVAILABLE","FINALIZATION_PERSISTENCE_ERROR","FINALIZATION_INTERNAL_ERROR");
    private static Map<String,Object> selection(ChatSelectedOutputFinalizationService.Selection s) {
        var out=new LinkedHashMap<String,Object>();out.put("requestId",s.requestId());out.put("sha256",s.sha256());out.put("title",s.title());out.put("purpose",s.purpose());
        if(s.formalArtifactSource()!=null) {
            var formal=s.formalArtifactSource();
            if(s.messageSource()!=null || s.requestId()!=null || s.stepId()!=null || s.outputId()!=null)throw invalid();
            out.remove("requestId");out.put("formalArtifactSource",Map.of("sourceDeliveryId",formal.sourceDeliveryId(),
                    "sourceArtifactId",formal.sourceArtifactId(),"sourceArtifactVersion",Integer.toString(formal.sourceArtifactVersion())));
        } else if(s.messageSource()==null){out.put("stepId",s.stepId());out.put("outputId",s.outputId());}
        else {var m=s.messageSource();if(m.finalDigest()==null||!m.finalDigest().matches("sha256:[0-9a-f]{64}"))throw invalid();
            out.put("messageSource",Map.of("turnId",m.turnId(),"messageId",m.messageId(),"snapshotId",m.snapshotId(),"finalSha256",m.finalDigest().substring(7)));}
        return out;
    }
    private static String decimal(String s,boolean positive){if(s==null||!s.matches(positive?"[1-9][0-9]*":"0|[1-9][0-9]*"))throw invalid();
        try{if(Long.parseLong(s)>9_007_199_254_740_991L)throw invalid();return s;}catch(NumberFormatException e){throw invalid();}}
    private static IllegalStateException invalid(){return new IllegalStateException("Finalization projection is incomplete");}
}
