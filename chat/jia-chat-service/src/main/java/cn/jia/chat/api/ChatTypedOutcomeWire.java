package cn.jia.chat.api;

import cn.jia.chat.service.ChatDeliberationException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Read-only HTTP projection AFTER the service verifies immutable model facts and original hashes.
 * Never pass this display object back to canonical validators, persistence, or dispatch. */
public final class ChatTypedOutcomeWire {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String,Object>> OBJECT = new TypeReference<>() {};
    private ChatTypedOutcomeWire() {}

    public static Map<String,Object> project(Object verified, String route) {
        var source = JSON.convertValue(verified, OBJECT);
        if (source == null || !(source.get("schemaVersion") instanceof Number version)
                || version.intValue()<1 || version.intValue()>4
                || !Set.of("CHAT","INSPECT").contains(route)) throw invalid();
        var result = select(source, "conversationId", "conversationGeneration", "requestId", "requestRevision", "turnId", "state");
        result.put("schemaVersion", 5); result.put("route", route);
        result.put("outcome", source.get("outcome") == null ? null : outcome(map(source.get("outcome")), version.intValue()));
        result.put("inspection", source.get("inspection") == null ? null : inspection(map(source.get("inspection"))));
        result.put("actionProgress", source.get("actionProgress") == null ? null : progress(map(source.get("actionProgress"))));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String,Object> outcome(Map<String,Object> old, int version) {
        var out = select(old, "outcomeId", "taskId", "assignmentRevision", "assistantMessageId", "kind", "text", "clarification");
        out.put("outcomeContractVersion", old.getOrDefault("outcomeContractVersion", version));
        out.put("finalSha256", hash(old.get("finalDigest")));
        out.put("proposal", old.get("proposal"));
        if (old.get("action") == null) out.put("action", null);
        else {var action = select(map(old.get("action")), "actionRequestId", "actionId", "instruction", "sourceRefIds");
            action.put("parameters", map(old.get("action")).get("parameters")); out.put("action", action);}
        out.put("deliverable", old.get("deliverable"));
        if (old.get("messageSource") == null) out.put("messageSource", null);
        else {var value=map(old.get("messageSource"));var message=select(value,"turnId","messageId","snapshotId");
            message.put("finalSha256",hash(value.get("finalDigest")));out.put("messageSource",message);}
        if (old.get("deliveryRelation") == null) out.put("deliveryRelation",null);
        else {var value=map(old.get("deliveryRelation"));var relation=select(value,"mode","parentOutcomeId");
            relation.put("parentFinalSha256",hash(value.get("parentFinalDigest")));
            relation.put("targetOutcomeId",value.get("targetOutcomeId"));
            relation.put("targetFinalSha256",value.get("targetFinalDigest")==null?null:hash(value.get("targetFinalDigest")));
            out.put("deliveryRelation",relation);}
        return out;
    }
    private static Map<String,Object> inspection(Map<String,Object> old) {
        var out=select(old,"authorizationId","sourceRefIds");out.put("manifestSha256",hash(old.get("manifestDigest")));
        if(old.get("inputSummary")==null)out.put("inputSummary",null);
        else {var value=map(old.get("inputSummary"));var summary=new LinkedHashMap<String,Object>();
            summary.put("inputSha256",hash(value.get("inputDigest")));
            if(!(value.get("sources") instanceof List<?> sources))throw invalid();
            summary.put("sources",sources.stream().map(item->{var raw=map(item);var receipt=select(raw,"sourceRefId","sha256","byteLength","carrier");
                receipt.put("contributionSha256",hash(raw.get("contributionDigest")));return receipt;}).toList());out.put("inputSummary",summary);}
        return out;
    }
    private static Map<String,Object> progress(Map<String,Object> old) {
        var out=select(old,"actionRequestId","state","dispatchVersion","childRequestId","childRoute","childStateVersion");
        for(String key:List.of("executionId","executionStateVersion","phase"))out.put(key,old.get(key));
        Object digest=old.get("resultDigest");if(digest!=null&&(!(digest instanceof String s)||!s.matches("[0-9a-f]{64}")))throw invalid();
        out.put("resultSha256",digest);return out;
    }
    private static LinkedHashMap<String,Object> select(Map<String,Object> source,String...keys) {
        var result=new LinkedHashMap<String,Object>();for(String key:keys){if(!source.containsKey(key))throw invalid();result.put(key,source.get(key));}return result;
    }
    private static Map<String,Object> map(Object value) {
        if(!(value instanceof Map<?,?>))throw invalid();return JSON.convertValue(value,OBJECT);
    }
    private static String hash(Object value) {
        if(!(value instanceof String s)||!s.matches("sha256:[0-9a-f]{64}"))throw invalid();return s.substring(7);
    }
    private static ChatDeliberationException invalid() {return new ChatDeliberationException(
            ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Typed outcome projection is incomplete");}
}
