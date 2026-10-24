package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import cn.jia.agent.service.AgentRuntimeV2ExecutionService;
import cn.jia.agent.service.AgentRuntimeV2EffectService;
import cn.jia.agent.service.impl.AgentRuntimeV2ExecutionServiceImpl;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import cn.jia.agent.service.impl.AgentRuntimeV2DispatchServiceImpl;
import com.fasterxml.jackson.core.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Strict raw registration BEFORE legacy parsing. The local map is only a route, never authority. */
@Component
public final class AgentRuntimeV2WebSocketAdapter {
    public static final String IDENTITY_ATTRIBUTE="runtimeV2OriginalHandshakeProof";
    private final ObjectProvider<AgentRuntimeV2RegistrationService> services;
    private final ObjectProvider<AgentRuntimeV2Readiness> readiness;
    private ObjectProvider<AgentRuntimeV2ExecutionService> execution;
    private ObjectProvider<AgentRuntimeV2EffectService> effects;
    private final String owner=UUID.randomUUID().toString();
    private final ConcurrentHashMap<String,Route> routes=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AgentRuntimeV2SocketTuple,Route> dispatchRoutes=new ConcurrentHashMap<>();
    private static final JsonFactory DISCRIMINATOR=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    // Classification may look past duplicate keys; only the strict raw codecs authorize requests.
    private static final JsonFactory EXECUTION_MARKERS=JsonFactory.builder().build();
    private static final class Route {
        final WebSocketSession session;final String fence=UUID.randomUUID().toString();final long epoch;
        final AgentRuntimeV2RegistrationContext identity;volatile AgentRuntimeV2SocketTuple committed;
        Route(WebSocketSession session,long epoch,AgentRuntimeV2RegistrationContext identity){this.session=session;this.epoch=epoch;this.identity=identity;}
    }
    public AgentRuntimeV2WebSocketAdapter(ObjectProvider<AgentRuntimeV2RegistrationService> services,ObjectProvider<AgentRuntimeV2Readiness> readiness){this.services=services;this.readiness=readiness;}
    @org.springframework.beans.factory.annotation.Autowired
    public void executionServices(ObjectProvider<AgentRuntimeV2ExecutionService> execution){this.execution=execution;}
    @org.springframework.beans.factory.annotation.Autowired
    public void effectServices(ObjectProvider<AgentRuntimeV2EffectService> effects){this.effects=effects;}
    public String ownerInstanceId(){return owner;}
    /** Bounded local-map routing seam. It does not assert database authority. */
    public boolean hasDispatchRoute(AgentRuntimeV2SocketTuple tuple){
        if(tuple==null||!owner.equals(tuple.socketOwnerInstanceId()))return false;
        Route r=dispatchRoutes.get(tuple);return r!=null&&tuple.equals(r.committed)&&r.session.isOpen()&&routes.get(r.session.getId())==r;
    }
    public boolean rawDispatch(AgentRuntimeV2SocketTuple tuple,byte[] original,long deadline,java.time.Clock clock){
        AgentRuntimeV2DispatchServiceImpl.outside();AgentRuntimeV2DispatchCodec.decode(original);
        Route r=dispatchRoutes.get(tuple);if(r!=null)synchronized(r){
            AgentRuntimeV2DispatchServiceImpl.outside();
            if(dispatchRoutes.get(tuple)!=r||routes.get(r.session.getId())!=r||!tuple.equals(r.committed)||!owner.equals(tuple.socketOwnerInstanceId())||!r.session.isOpen()||clock.millis()>=deadline||Thread.currentThread().isInterrupted())return false;
            try{r.session.sendMessage(new TextMessage(original));return true;}catch(java.io.IOException failure){return false;}
        }
        return false;
    }
    /** Fence local routing synchronously; potentially blocking socket close is separate cleanup. */
    public Runnable retireDispatchRoute(AgentRuntimeV2SocketTuple tuple){
        AgentRuntimeV2DispatchServiceImpl.outside();var retired=new java.util.ArrayList<WebSocketSession>();
        Route r=dispatchRoutes.remove(tuple);if(r!=null&&tuple.equals(r.committed)&&routes.remove(r.session.getId(),r))retired.add(r.session);
        return ()->{AgentRuntimeV2DispatchServiceImpl.outside();retired.forEach(AgentRuntimeV2WebSocketAdapter::protocolClose);};
    }
    private static <T>T available(ObjectProvider<T> provider){try{return provider==null?null:provider.getIfAvailable();}catch(RuntimeException unavailable){return null;}}
    public int[] supportedProtocolVersions(){var ready=available(readiness);return ready==null?new int[]{1}:ready.supportedProtocolVersions();}
    public void established(WebSocketSession session){Object identity=session.getAttributes().get(IDENTITY_ATTRIBUTE);if(identity instanceof AgentRuntimeV2RegistrationContext context)routes.putIfAbsent(session.getId(),new Route(session,0,context));}
    public boolean intercept(WebSocketSession session,byte[] original,Consumer<TextMessage> sender) {
        String type=discriminator(original);
        if(Set.of("command.execution.control.inspect","command.execution.effect.request","command.execution.reconcile.request").contains(Objects.toString(type,"")))return effects(session,original,type,sender);
        if(type!=null&&(type.startsWith("command.execution.")||type.equals("command.ack")||type.equals("execution.invalid")))return execution(session,original,type,sender);
        if(type==null||!type.startsWith("runtime.registration."))return false;
        AgentRuntimeV2RegistrationServiceImpl.outside();
        String kind;String id;
        try {
            switch(type) {
                case "runtime.registration.create" -> {var request=AgentRuntimeV2RegistrationCodec.decodeCreate(original);kind="CREATE";id=request.registrationRequestId();}
                case "runtime.registration.reconnect.challenge.request" -> {var request=AgentRuntimeV2RegistrationCodec.decodeChallengeRequest(original);kind="CHALLENGE";id=request.challengeRequestId();}
                case "runtime.registration.reconnect.commit" -> {var request=AgentRuntimeV2RegistrationCodec.decodeCommit(original);kind="RECONNECT";id=request.reconnectRequestId();}
                default -> {protocolClose(session);return true;}
            }
        } catch(IllegalArgumentException malformed){protocolClose(session);return true;}
        Route route=routes.get(session.getId());var ready=available(readiness);var service=available(services);
        if(ready==null||!ready.ready()||service==null) {send(sender,rejected(kind,id,"UNAVAILABLE"));return true;}
        if(route==null||route.session!=session){send(sender,rejected(kind,id,"NOT_AUTHORIZED"));return true;}
        synchronized(route) {
            if(routes.get(session.getId())!=route){send(sender,rejected(kind,id,"NOT_AUTHORIZED"));return true;}
            long epoch=route.committed==null?route.epoch:route.committed.socketOwnerEpoch();
            AgentRuntimeV2RegistrationService.Result result;
            try {result=switch(kind){case "CREATE"->service.create(route.identity,route.fence,owner,epoch,original);case "CHALLENGE"->service.challenge(route.identity,route.fence,owner,epoch,original);default->service.reconnect(route.identity,route.fence,owner,epoch,original);};}
            catch(RuntimeException unavailable){send(sender,rejected(kind,id,"UNAVAILABLE"));return true;}
            AgentRuntimeV2RegistrationServiceImpl.outside();
            if(result.currentSocket()!=null){if(route.committed!=null)dispatchRoutes.remove(route.committed,route);route.committed=result.currentSocket();dispatchRoutes.put(route.committed,route);} // before a potentially lost network response
            if(result.replacedSocket()!=null)for(Route old:routes.values())if(old!=route&&result.replacedSocket().equals(old.committed)){dispatchRoutes.remove(old.committed,old);protocolClose(old.session);}
            send(sender,result.responseBytes());
        }
        return true;
    }
    /** Original strict additive bytes stay unmodified. A new-process recovery
     * route may have no committed execution socket; that absence stays exact
     * through the reducer and the independent post-cleanup ACL check.
     */
    private boolean effects(WebSocketSession session,byte[] raw,String type,Consumer<TextMessage> sender){
        AgentRuntimeV2ExecutionServiceImpl.outside();
        AgentRuntimeV2EffectCodec.Request request;
        try{
            request=AgentRuntimeV2EffectCodec.decode(raw);
            boolean exact=switch(type){case "command.execution.control.inspect"->request instanceof AgentRuntimeV2EffectCodec.Inspect;case "command.execution.effect.request"->request instanceof AgentRuntimeV2EffectCodec.EffectRequest;case "command.execution.reconcile.request"->request instanceof AgentRuntimeV2EffectCodec.Reconcile;default->false;};
            if(!exact)throw new IllegalArgumentException("effect family mismatch");
        }catch(IllegalArgumentException malformed){protocolClose(session);return true;}
        var service=available(effects);var ready=available(readiness);var route=routes.get(session.getId());
        if(service==null||ready==null||!ready.ready()||route==null||route.session!=session){protocolClose(session);return true;}
        synchronized(route){
            var socket=route.committed;
            // Unregistered EFFECT enters the service's receipt-only exact
            // replay branch; it cannot reach ordinary effect preparation.
            // CONTROL still requires a current registered tuple.
            if(!effectRouteCurrent(route,session,socket)||socket==null&&type.equals("command.execution.control.inspect")){protocolClose(session);return true;}
            var trusted=new AgentRuntimeV2EffectService.TrustedRoute(route.fence,owner,socket==null?route.epoch:socket.socketOwnerEpoch());
            AgentRuntimeV2EffectService.Result result;
            try{
                trusted.requireSocket(socket);
                result=switch(type){case "command.execution.control.inspect"->service.inspect(route.identity,socket,trusted,raw);case "command.execution.effect.request"->service.effect(route.identity,socket,trusted,raw);default->service.reconcile(route.identity,socket,trusted,raw);};
            }catch(RuntimeException failure){AgentRuntimeV2ExecutionServiceImpl.outside();protocolClose(session);return true;}
            AgentRuntimeV2ExecutionServiceImpl.outside();
            if(result.close()){protocolClose(session);return true;}
            String requestId=request instanceof AgentRuntimeV2EffectCodec.Inspect r?r.grant().requestId():request instanceof AgentRuntimeV2EffectCodec.EffectRequest r?r.grant().requestId():((AgentRuntimeV2EffectCodec.Reconcile)request).requestId();
            String dispatch=request instanceof AgentRuntimeV2EffectCodec.Inspect r?r.grant().runtimeDispatchId():request instanceof AgentRuntimeV2EffectCodec.EffectRequest r?r.grant().runtimeDispatchId():((AgentRuntimeV2EffectCodec.Reconcile)request).runtimeDispatchId();
            for(byte[] response:result.responses()){
                if(!effectRouteCurrent(route,session,socket))break;
                try{
                    var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(response);
                    String expected=type.replace(".inspect",".receipt").replace(".request",".receipt");
                    if(!receipt.messageType().equals(expected)||!receipt.requestId().equals(requestId)||!receipt.runtimeDispatchId().equals(dispatch)||!receipt.requestSha256().equals(AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(raw))))throw new IllegalArgumentException("effect response original request mismatch");
                    if(!service.current(route.identity,socket,trusted,response))break;
                }catch(RuntimeException malformed){AgentRuntimeV2ExecutionServiceImpl.outside();protocolClose(session);break;}
                AgentRuntimeV2ExecutionServiceImpl.outside();
                // ACL may block while nonblocking route retirement completes.
                // An initially absent socket must still be absent; never borrow
                // a registration committed during the authority read.
                if(!effectRouteCurrent(route,session,socket))break;
                send(sender,response);
            }
        }
        return true;
    }
    private boolean effectRouteCurrent(Route route,WebSocketSession session,AgentRuntimeV2SocketTuple socket){
        return routes.get(session.getId())==route&&route.session==session&&session.isOpen()
            &&Objects.equals(route.committed,socket)&&(socket==null||owner.equals(socket.socketOwnerInstanceId())&&route.fence.equals(socket.sessionFence())&&dispatchRoutes.get(socket)==route);
    }
    private boolean execution(WebSocketSession session,byte[] raw,String type,Consumer<TextMessage> sender){
        AgentRuntimeV2ExecutionServiceImpl.outside();
        try{switch(type){case "command.ack"->AgentRuntimeV2ExecutionCodec.decodeAck(raw);case "command.execution.continue"->AgentRuntimeV2ExecutionCodec.decodeContinue(raw);case "command.execution.renew"->AgentRuntimeV2ExecutionCodec.decodeRenew(raw);default->throw new IllegalArgumentException("unknown execution wire");}}
        catch(IllegalArgumentException malformed){protocolClose(session);return true;}
        var service=available(execution);var ready=available(readiness);var route=routes.get(session.getId());
        if(service==null||ready==null||!ready.ready()||route==null||route.session!=session||route.committed==null){protocolClose(session);return true;}
        synchronized(route){
            if(routes.get(session.getId())!=route||!route.session.isOpen()){protocolClose(session);return true;}
            var tuple=route.committed;AgentRuntimeV2ExecutionService.Result result;
            try{result=switch(type){case "command.ack"->service.acknowledge(route.identity,tuple,raw);case "command.execution.continue"->service.continueExecution(route.identity,tuple,raw);default->service.renewExecution(route.identity,tuple,raw);};}
            catch(RuntimeException failure){AgentRuntimeV2ExecutionServiceImpl.outside();protocolClose(session);return true;}
            AgentRuntimeV2ExecutionServiceImpl.outside();
            if(result.close()){protocolClose(session);return true;}
            for(byte[] response:result.responses()){
                AgentRuntimeV2ExecutionServiceImpl.outside();if(routes.get(session.getId())!=route||!tuple.equals(route.committed)||dispatchRoutes.get(tuple)!=route||!session.isOpen())break;
                // The outer delivery is not authority. Decode original inner bytes for a fresh result-time fence.
                try(var p=DISCRIMINATOR.createParser(response)){String message=null,encoded=null;while(p.nextToken()!=null){if(p.currentToken()==JsonToken.FIELD_NAME){String k=p.currentName();p.nextToken();if(k.equals("messageType"))message=p.getValueAsString();if(k.equals("grantBytesBase64"))encoded=p.getValueAsString();}}if("command.execution.grant.delivery".equals(message)&&(encoded==null||!service.current(route.identity,tuple,Base64.getDecoder().decode(encoded))))break;}
                catch(java.io.IOException|IllegalArgumentException malformed){protocolClose(session);break;}
                AgentRuntimeV2ExecutionServiceImpl.outside();
                // current() may wait while nonblocking retirement invalidates an otherwise-open socket.
                // Re-admit the response against the complete local fence after authority cleanup.
                if(routes.get(session.getId())!=route||route.session!=session||!tuple.equals(route.committed)||dispatchRoutes.get(tuple)!=route||!owner.equals(tuple.socketOwnerInstanceId())||!session.isOpen())break;
                send(sender,response);
            }
        }
        return true;
    }
    public void closed(WebSocketSession session) {Route route=routes.get(session.getId());if(route!=null&&route.session==session&&routes.remove(session.getId(),route)&&route.committed!=null){dispatchRoutes.remove(route.committed,route);var service=available(services);if(service!=null){AgentRuntimeV2RegistrationServiceImpl.outside();service.disconnect(route.committed,"SOCKET_CLOSED");}}}
    @Scheduled(fixedDelay=10000) public void maintainRouting() {
        AgentRuntimeV2RegistrationServiceImpl.outside();var ready=available(readiness);if(ready==null||!ready.ready())return;var service=available(services);if(service==null)return;
        for(Route route:routes.values())if(route.committed!=null)try{if(!route.session.isOpen()||!service.renew(route.identity,route.committed))protocolClose(route.session);}catch(RuntimeException unavailable){protocolClose(route.session);}
        service.reapExpired(100);
    }
    private static byte[] rejected(String kind,String id,String reason){return AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected(kind,id,null,reason));}
    private static void send(Consumer<TextMessage> sender,byte[] raw){AgentRuntimeV2RegistrationServiceImpl.outside();sender.accept(new TextMessage(raw));}
    private static void protocolClose(WebSocketSession session){AgentRuntimeV2RegistrationServiceImpl.outside();try{session.close(CloseStatus.PROTOCOL_ERROR);}catch(java.io.IOException ignored){}}
    public static void malformedText(WebSocketSession session){protocolClose(session);}
    /** Classification only. It never reserializes or supplies decoded fields to authorization. */
    private static String discriminator(byte[] raw) {
        String type=null;boolean v2Ack=false,executionSeen=false;
        try(var parser=DISCRIMINATOR.createParser(raw)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)return executionMarker(raw)?"execution.invalid":registrationMarker(raw)?"runtime.registration.invalid":null;
            while(parser.nextToken()!=JsonToken.END_OBJECT){String field=parser.currentName();if(parser.nextToken()==null)throw new IllegalArgumentException();
                if("messageType".equals(field)&&parser.currentToken()==JsonToken.VALUE_STRING){type=parser.getText();executionSeen|=type.startsWith("command.execution.")||type.equals("command.ack");}
                else if("schemaVersion".equals(field)){v2Ack|=parser.currentToken()!=JsonToken.VALUE_NUMBER_INT||!"1".equals(parser.getText());}
                else if(Set.of("runtimeDispatchId","ackMessageId").contains(field)){v2Ack=true;}
                parser.skipChildren();
            }
            if(parser.nextToken()!=null)throw new IllegalArgumentException();
            // The established V1 ACK (schemaVersion=1, or its legacy absent version) stays on V1.
            if(executionMarker(raw)&&!(type!=null&&(type.startsWith("command.execution.")||type.equals("command.ack"))))return "execution.invalid";
            return "command.ack".equals(type)&&!v2Ack?null:type;
        }catch(Exception malformed){return executionSeen||executionMarker(raw)?"execution.invalid":registrationMarker(raw)?"runtime.registration.invalid":null;}
    }
    private static boolean registrationMarker(byte[] raw){return new String(raw,StandardCharsets.UTF_8).contains("runtime.registration.");}
    private static boolean executionMarker(byte[] raw){
        boolean execution=false,ack=false,v2=false,executionField=false;
        // Strip a BOM only to recognize malformed V2; original bytes still go to the strict codec.
        int offset=raw.length>=3&&(raw[0]&255)==239&&(raw[1]&255)==187&&(raw[2]&255)==191?3:0;
        try(var parser=EXECUTION_MARKERS.createParser(raw,offset,raw.length-offset)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)return false;
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                if(parser.currentToken()!=JsonToken.FIELD_NAME)break;
                String field=parser.currentName();if(parser.nextToken()==null)break;
                if(Set.of("messageType","message_type","message-type","type").contains(field)&&parser.currentToken()==JsonToken.VALUE_STRING){String value=parser.getText();execution|=value.startsWith("command.execution.");ack|=value.equals("command.ack");}
                else if("schemaVersion".equals(field))v2|=parser.currentToken()!=JsonToken.VALUE_NUMBER_INT||!"1".equals(parser.getText());
                else if(Set.of("runtimeDispatchId","ackMessageId","renewalRequestId","continuationRequestId","startedReceiptId","previousExecutionGrantId").contains(field))executionField=true;
                // V1 content and nested metadata are never protocol routing signals.
                parser.skipChildren();
            }
        }catch(Exception malformed){/* Retain only already-decoded top-level routing signals. */}
        return execution||ack&&(v2||executionField)||v2&&executionField;
    }
    public static byte[] originalText(String text){try{ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));byte[] raw=new byte[bytes.remaining()];bytes.get(raw);return raw;}catch(CharacterCodingException malformed){throw new IllegalArgumentException("invalid UTF-8 text",malformed);}}
}
