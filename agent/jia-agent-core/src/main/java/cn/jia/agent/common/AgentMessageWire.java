package cn.jia.agent.common;

import cn.jia.core.entity.ProtocolMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Network-only projection. Never apply to canonical business bytes or durable result hashes. */
public final class AgentMessageWire {
    private AgentMessageWire() { }
    private static final Set<String> ROOT = Set.of("protocolVersion","requestId","messageType","messageId","data");
    private static final Set<String> REPLY = Set.of("protocolVersion","requestId","messageType","messageId","data","correlationId","code","msg");
    private static final Map<String,String> TYPES = Map.ofEntries(
        Map.entry("connected","connection.established"),Map.entry("protocol_hello","protocol.hello"),
        Map.entry("protocol_error","protocol.error"),Map.entry("error","protocol.error"),
        Map.entry("ping","connection.ping"),Map.entry("pong","connection.pong"),
        Map.entry("started","chat.stream.started"),Map.entry("delta","chat.stream.delta"),
        Map.entry("done","chat.stream.completed"),Map.entry("stopped","chat.stream.stopped"),
        Map.entry("agent_registered","agent.registered"),Map.entry("agent_capability_index","agent.capability.index"),
        Map.entry("agent_status_updated","agent.status.updated"),Map.entry("task_reported","task.report.receipt"),
        Map.entry("chat_dispatch_acknowledged","chat.dispatch.receipt"),Map.entry("agent_message","chat.message"),
        Map.entry("agent_message_delta","chat.message.delta"),Map.entry("agent_message_saved","chat.message.receipt"),
        Map.entry("agent_direct_message","chat.message"),Map.entry("task_event","task.event"));
    private static final Set<String> TIMES = Set.of("issuedAt","sentAt","occurredAt","expiresAt");
    private static final Set<String> LONGS = Set.of("sessionGeneration","requestRevision","conversationGeneration","deltaSeq","sequence","grantRevision","deliveryEpoch","fencingToken","eventSequence","eventVersion","finalSeq");
    public static String type(String type) { return TYPES.getOrDefault(type,type); }
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid message identity");
        return text;
    }
    @SuppressWarnings("unchecked")
    public static ProtocolMessage<Map<String,Object>> decode(Map<String,Object> root) {
        boolean reply=root.containsKey("correlationId")||root.containsKey("code")||root.containsKey("msg");
        if(!root.keySet().equals(reply?REPLY:ROOT)||!Integer.valueOf(1).equals(root.get("protocolVersion"))||!(root.get("data") instanceof Map<?,?>))
            throw new IllegalArgumentException("Invalid message envelope");
        var data=new LinkedHashMap<String,Object>((Map<String,Object>)root.get("data"));
        if(data.containsKey("messageType")||data.containsKey("messageId")||data.containsKey("protocolVersion")||data.containsKey("type")||data.containsKey("channel"))
            throw new IllegalArgumentException("Duplicate message metadata");
        return new ProtocolMessage<>(1,text(root.get("requestId")),text(root.get("messageType")),text(root.get("messageId")),
                data,reply?text(root.get("correlationId")):null,reply?text(root.get("code")):null,reply?Objects.toString(root.get("msg"),null):null);
    }
    public static Map<String,Object> business(ProtocolMessage<Map<String,Object>> message) {
        var data=new LinkedHashMap<>(message.data());
        String type=switch(message.messageType()){case "connection.ping"->"ping";case "connection.pong"->"pong";default->message.messageType();};
        if(TYPES.containsKey(type)&&!type.equals("ping")&&!type.equals("pong"))throw new IllegalArgumentException("Legacy wire type");
        boolean skill="SKILL_INSTALL_RESULT".equals(data.get("resultType"))||"SKILL_INSTALL".equals(data.get("commandType"));
        if(data.containsKey("packageDigest")&&skill)throw new IllegalArgumentException("Legacy package digest");
        if(skill&&data.containsKey("packageSha256")) {
            String sha=text(data.remove("packageSha256"));if(!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid package SHA");
            data.put("packageDigest","sha256:"+sha);
        }
        if("SKILL_INSTALL_RESULT".equals(data.get("resultType"))&&data.get("installedAt")!=null) {
            Object at=data.get("installedAt");if(!(at instanceof Integer||at instanceof Long)||((Number)at).longValue()<0||((Number)at).longValue()>9007199254740991L)throw new IllegalArgumentException("Invalid installedAt");
            data.put("installedAt",Long.toString(((Number)at).longValue()));
        }
        if(data.containsKey("installationId"))throw new IllegalArgumentException("Legacy installation field");
        if(skill)rename(data,"skillInstallationId","installationId");
        else rename(data,"runtimeInstallationId","installationId");
        if("SKILL_INSTALL_RESULT".equals(data.get("resultType"))&&data.containsKey("executionRuntimeInstanceId")) {
            data.remove("runtimeInstanceId"); rename(data,"executionRuntimeInstanceId","runtimeInstanceId");
        }
        // A session generation is consumed as a Java long by the private proof verifier.
        if(data.containsKey("sessionGeneration"))data.put("sessionGeneration",decimal(data.get("sessionGeneration")));
        for(String field:LONGS)if(!field.equals("sessionGeneration")&&data.containsKey(field)&&!(data.get(field) instanceof String))
            throw new IllegalArgumentException("Non-string revision");
        for(String field:TIMES)if(data.containsKey(field)&&(!(data.get(field) instanceof Integer||data.get(field) instanceof Long)||((Number)data.get(field)).longValue()<0||((Number)data.get(field)).longValue()>9007199254740991L))throw new IllegalArgumentException("Invalid public time");
        data.put("messageType",type);data.put("messageId",message.messageId());
        if(!"SKILL_INSTALL_RESULT".equals(data.get("resultType")))data.putIfAbsent("requestId",message.requestId());
        if(message.correlationId()!=null)data.put("correlationId",message.correlationId());
        return data;
    }
    private static long decimal(Object value) {
        if(!(value instanceof String text)||!text.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid revision");
        return Long.parseLong(text);
    }
    private static void rename(Map<String,Object> data,String from,String to) {
        if(!data.containsKey(from))return;
        if(data.containsKey(to))throw new IllegalArgumentException("Ambiguous field");
        data.put(to,data.remove(from));
    }
    public static ProtocolMessage<Map<String,Object>> event(String privateType,Map<String,?> body,ProtocolMessage<?> original) {
        var data=new LinkedHashMap<String,Object>();if(body!=null)data.putAll(body);
        String canonical=type(privateType);
        if("agent_direct_message".equals(privateType)&&data.get("messageType") instanceof String declared)canonical=type(declared);
        data.remove("messageType");data.remove("type");data.remove("channel");
        String id=data.remove("messageId") instanceof String value?value:UUID.randomUUID().toString();
        String requestId=data.get("requestId") instanceof String value?value:id;
        boolean receipt=canonical.endsWith(".receipt")||Set.of("agent.registered","agent.status.updated","agent.capability.index","connection.pong").contains(canonical);
        String correlation=null,code=null,msg=null;
        if(original!=null&&(receipt||canonical.equals("protocol.error"))) {
            requestId=original.requestId();correlation=original.messageId();
            if(id.equals(correlation))id=replyId(correlation,canonical);
        } else if(receipt&&data.get("correlationId") instanceof String value)correlation=value;
        if(correlation!=null){data.remove("correlationId");code=data.remove("code") instanceof String value?value:"E0";msg=data.remove("message") instanceof String value?value:"ok";}
        if(data.containsKey("timestamp")){Object at=data.remove("timestamp");data.putIfAbsent("occurredAt",at);}
        for(String field:TIMES)if(data.get(field) instanceof String value){long at=decimal(value);if(at>9007199254740991L)throw new IllegalArgumentException("Invalid time");data.put(field,at);}
        for(String field:LONGS)if(data.get(field) instanceof Number n)data.put(field,Long.toString(n.longValue()));
        boolean skill="SKILL_INSTALL_RESULT".equals(data.get("resultType"))||"SKILL_INSTALL".equals(data.get("commandType"));
        if(skill&&data.containsKey("packageDigest")) {
            String digest=text(data.remove("packageDigest"));if(!digest.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid package digest");
            data.put("packageSha256",digest.substring(7));
        }
        if(data.get("runtimeAuth") instanceof Map<?,?> auth) {
            var projected=new LinkedHashMap<String,Object>();auth.forEach((key,value)->projected.put((String)key,value));
            rename(projected,"installationId","runtimeInstallationId");
            if(projected.get("sessionGeneration") instanceof Number n)projected.put("sessionGeneration",Long.toString(n.longValue()));
            data.put("runtimeAuth",projected);
        }
        rename(data,"installationId",skill?"skillInstallationId":"runtimeInstallationId");
        if("SKILL_INSTALL_RESULT".equals(data.get("resultType"))&&data.containsKey("runtimeInstanceId"))rename(data,"runtimeInstanceId","executionRuntimeInstanceId");
        return new ProtocolMessage<>(1,requestId,canonical,id,data,correlation,code,msg);
    }
    private static String replyId(String id,String type) {
        try{return "reply_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+'\n'+type).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
