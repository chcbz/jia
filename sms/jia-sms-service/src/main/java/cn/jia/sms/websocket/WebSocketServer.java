package cn.jia.sms.websocket;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.ProtocolMessage;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.type.TypeReference;
import cn.jia.core.util.CollectionUtil;
import cn.jia.core.util.StringUtil;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * @author chc
 */
@Slf4j
@ServerEndpoint(value = "/websocket")
@Component
@EqualsAndHashCode
public class WebSocketServer {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final TypeReference<Map<String,Object>> MESSAGE = new TypeReference<>() { };
    private void transmit(ProtocolMessage<?> message) throws IOException { session.getBasicRemote().sendText(JSON.writeValueAsString(message)); }
    private void event(String type,Object data) throws IOException {String id=UUID.randomUUID().toString();transmit(ProtocolMessage.event(id,type,id,data));}

    /**
     * 静态变量，用来记录当前在线连接数。应该把它设计成线程安全的。
     */
    private static int onlineCount = 0;
    /**
     * concurrent包的线程安全Set，用来存放每个客户端对应的MyWebSocket对象。
     */
    private static final CopyOnWriteArraySet<WebSocketServer> WEB_SOCKET_SET = new CopyOnWriteArraySet<>();
    /**
     * 与某个客户端的连接会话，需要通过它来给客户端发送数据
     */
    private Session session;
    /**
     * 保存当前登录用户ID
     */
    private String loginUser;

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session) {
        try {
            // 设置当前session
            this.session = session;
            String token = session.getRequestParameterMap().get("access_token").getFirst();
            this.loginUser = EsSecurityHandler.currentContext(token).getJiacn();
            // 当前登录用户校验 每个用户同时只能连接一次
            WebSocketServer currentWebSocket = getCurrentWebSocket(this.loginUser);
            if (currentWebSocket != null) {
                event("sms.connection.rejected",Map.of("reasonCode","ALREADY_CONNECTED"));
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,"Already connected"));
                return;
            }
            // 将当前websocket加入set中
            WEB_SOCKET_SET.add(this);
            addOnlineCount(); // 在线数加1
            event("sms.connection.established",Map.of("connected",true));
            log.info("有一新连接！当前在线人数为{}", getOnlineCount());
        } catch (Exception rejected) {
            try { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,"Authentication required")); } catch(IOException ignored) { }
            log.warn("SMS WebSocket authentication failed");
        }
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        // 从set中删除
        boolean b = WEB_SOCKET_SET.remove(this);
        if (b && getOnlineCount() > 0) {
            // 在线数减1
            subOnlineCount();
        }
        log.info("有一连接关闭！当前在线人数为{}", getOnlineCount());
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            if(this.session!=session||!WEB_SOCKET_SET.contains(this)||StringUtil.isEmpty(loginUser)) {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,"Authentication required"));return;
            }
            var request=ProtocolMessage.decode(JSON.readValue(message,MESSAGE));
            if(request.correlationId()!=null||!request.messageType().equals("sms.message")||!(request.data() instanceof Map<?,?> data)
                    ||!data.keySet().equals(Set.of("content"))||!(data.get("content") instanceof String))throw new IllegalArgumentException("Invalid SMS message");
            transmit(ProtocolMessage.reply(request,"sms.message.receipt",UUID.randomUUID().toString(),"E0","ok",request.data()));
        } catch(Exception invalid) {
            try { event("sms.protocol.error",Map.of("reasonCode","MESSAGE_INVALID")); } catch(IOException failed) { log.warn("SMS WebSocket delivery failed"); }
        }
    }

    /**
     * 发生错误时调用
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("websocket发生错误  : 登录人ID = {}, 错误信息 = {}", this.loginUser, error.getMessage());
    }

    public static synchronized int getOnlineCount() {
        return onlineCount;
    }

    public static synchronized void addOnlineCount() {
        WebSocketServer.onlineCount++;
    }

    public static synchronized void subOnlineCount() {
        WebSocketServer.onlineCount--;
    }

    /**
     * 根据当前登录用户ID获取他的websocket对象
     *
     * @param loginUser 用户ID
     * @return MyWebSocket
     * @author hufx
     * @since 2017年6月2日上午10:35:32
     */
    public static WebSocketServer getCurrentWebSocket(String loginUser) {
        if (StringUtil.isEmpty(loginUser) || WEB_SOCKET_SET.isEmpty()) {
            return null;
        }
        for (WebSocketServer socketServer : WEB_SOCKET_SET) {
            if (socketServer.loginUser.equals(loginUser)) {
                return socketServer;
            }
        }
        return null;
    }

    /**
     * 给当前用户发消息（单条）
     *
     * @param message 消息
     * @throws IOException void
     * @author hufx
     * @since 2017年6月1日下午2:05:36
     */
    public void sendMessage(String message) throws IOException {
        event("sms.message",Map.of("content",message));
        // this.session.getAsyncRemote().sendText(message);
    }

    /**
     * 给指定用户发指定消息（单人单条）
     *
     * @param loginUser 用户ID
     * @param message   消息 void
     * @author hufx
     * @since 2017年6月2日上午11:13:26
     */
    public static void sendMessage(String message, String loginUser) {
        try {
            if (StringUtil.isEmpty(loginUser) || StringUtil.isBlank(message)) {
                return;
            }
            WebSocketServer currentWebSocket = getCurrentWebSocket(loginUser);
            if (currentWebSocket == null) {
                return;
            }
            currentWebSocket.sendMessage(message);
        } catch (IOException e) {
            log.error("websocket发送消息异常  : 登录人ID = {} , Exception = {}", loginUser, e.getMessage());
        }
    }

    /**
     * 给指定人群发消息（单条）
     *
     * @param loginUserList 用户ID列表
     * @param message       消息 void
     * @author hufx
     * @since 2017年6月2日上午11:25:29
     */
    public static void sendMessageList(String message, List<String> loginUserList) {
        try {
            if (CollectionUtil.isNullOrEmpty(loginUserList) || StringUtil.isBlank(message)) {
                return;
            }
            for (String loginUser : loginUserList) {
                WebSocketServer currentWebSocket = getCurrentWebSocket(loginUser);
                if (currentWebSocket == null) {
                    continue;
                }
                currentWebSocket.sendMessage(message);
            }
        } catch (Exception e) {
            log.error("websocket发送消息异常  : 登录人ID = {} , Exception = {}", loginUserList, e.getMessage());
        }
    }

    /**
     * 给所有在线用户发消息（单条）
     *
     * @param message 消息
     * @author hufx
     * @since 2017年6月2日上午11:11:05
     */
    public static void sendMessageAll(String message) {
        try {
            if (WEB_SOCKET_SET.isEmpty() || StringUtil.isBlank(message)) {
                return;
            }
            for (WebSocketServer item : WEB_SOCKET_SET) {
                item.sendMessage(message);
            }
        } catch (IOException e) {
            log.error("websocket发送消息异常  : Exception = {}", e.getMessage());
        }
    }

}
