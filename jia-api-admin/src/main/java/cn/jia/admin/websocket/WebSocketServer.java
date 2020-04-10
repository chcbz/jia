package cn.jia.admin.websocket;

import cn.jia.admin.config.security.SecurityConfiguration;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.JSONUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@ServerEndpoint(value = "/websocket")
@Component
public class WebSocketServer {

	private static int onlineCount = 0; // 静态变量，用来记录当前在线连接数。应该把它设计成线程安全的。

	private static CopyOnWriteArraySet<WebSocketServer> webSocketSet = new CopyOnWriteArraySet<WebSocketServer>(); // concurrent包的线程安全Set，用来存放每个客户端对应的MyWebSocket对象。

	private Session session; // 与某个客户端的连接会话，需要通过它来给客户端发送数据

	private Integer fuserid; // 保存当前登录用户ID

	/**
	 * 连接建立成功调用的方法
	 */
	@OnOpen
	public void onOpen(Session session) {
		try {
			this.session = session; // 设置当前session
			String token = session.getRequestParameterMap().get("access_token").get(0);
			User user = SecurityConfiguration.tokenUser(token);
			this.fuserid = user.getId();
			WebSocketServer _this = getCurrentWebSocket(this.fuserid); // 当前登录用户校验 每个用户同时只能连接一次
			if (_this != null) {
				sendMessage(JSONUtil.toJson(JSONResult.failure("E999", "您已有连接信息，不能重复连接 !")));
				return;
			}
			webSocketSet.add(this); // 将当前websocket加入set中
			addOnlineCount(); // 在线数加1
			sendMessage(JSONUtil.toJson(JSONResult.success("连接成功！")));
			System.out.println("有一新连接！当前在线人数为" + getOnlineCount());
		} catch (IOException e) {
			System.out.println("连接异常！");
			log.error("websocket连接异常  : 登录人ID = " + this.fuserid + " , Exception = " + e.getMessage());
		}
	}

	/**
	 * 连接关闭调用的方法
	 */
	@OnClose
	public void onClose() {
		boolean b = webSocketSet.remove(this); // 从set中删除
		if (b && getOnlineCount() > 0) {
			subOnlineCount(); // 在线数减1
		}
		System.out.println("有一连接关闭！当前在线人数为" + getOnlineCount());
	}

	/**
	 * 收到客户端消息后调用的方法
	 *
	 * @param message
	 *            客户端发送过来的消息
	 */
	@OnMessage
	public void onMessage(String message, Session session) {
		try {
			WebSocketServer _this = null;
			for (WebSocketServer item : webSocketSet) {
				if (item.session.getId() == session.getId()) {
					_this = item;
				}
			}
			JSONResult<String> result = JSONResult.success("");
			result.setMsg("reply");
			if (_this == null) {
				result.setData("未连接不能发送消息！");
				this.sendMessage(JSONUtil.toJson(result));
				return;
			}
			System.out.println("来自客户端的消息:" + message);
			result.setData(message);
			this.sendMessage(JSONUtil.toJson(result));
		} catch (IOException e) {
			System.out.println("发送消息异常！");
			log.error("websocket发送消息异常  : 登录人ID = " + this.fuserid + " , Exception = " + e.getMessage());
		}
	}

	/**
	 * 发生错误时调用
	 */
	@OnError
	public void onError(Session session, Throwable error) {
		System.out.println("发生错误！");
		log.error("websocket发生错误  : 登录人ID = " + this.fuserid);
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
	 * @param fuserid
	 *            用户ID
	 * @return MyWebSocket
	 * @author hufx
	 * @date 2017年6月2日上午10:35:32
	 */
	public static WebSocketServer getCurrentWebSocket(Integer fuserid) {
		if (fuserid == null || fuserid < 1 || webSocketSet == null || webSocketSet.size() < 1) {
			return null;
		}
		Iterator<WebSocketServer> iterator = webSocketSet.iterator();
		while (iterator.hasNext()) {
			WebSocketServer _this = iterator.next();
			if (_this.fuserid == fuserid) {
				return _this;
			}
		}
		return null;
	}

	/**
	 * 给当前用户发消息（单条）
	 * 
	 * @param message
	 *            消息
	 * @throws IOException
	 *             void
	 * @author hufx
	 * @date 2017年6月1日下午2:05:36
	 */
	public void sendMessage(String message) throws IOException {
		this.session.getBasicRemote().sendText(message);
		// this.session.getAsyncRemote().sendText(message);
	}

	/**
	 * 给指定用户发指定消息（单人单条）
	 * 
	 * @param fuserid
	 *            用户ID
	 * @param message
	 *            消息 void
	 * @author hufx
	 * @date 2017年6月2日上午11:13:26
	 */
	public static void sendMessage(String message, Integer fuserid) {
		try {
			if (fuserid == null || fuserid < 1 || StringUtils.isBlank(message)) {
				return;
			}
			WebSocketServer _this = getCurrentWebSocket(fuserid);
			if (_this == null) {
				return;
			}
			_this.sendMessage(message);
		} catch (IOException e) {
			System.out.println("发送消息异常！");
		}
	}

	/**
	 * 给指定人群发消息（单条）
	 * 
	 * @param fuseridList
	 *            用户ID列表
	 * @param message
	 *            消息 void
	 * @author hufx
	 * @date 2017年6月2日上午11:25:29
	 */
	public static void sendMessageList(String message, List<Integer> fuseridList) {
		try {
			if (fuseridList == null || fuseridList.size() < 1 || StringUtils.isBlank(message)) {
				return;
			}
			for (Integer fuserid : fuseridList) {
				WebSocketServer _this = getCurrentWebSocket(fuserid);
				if (_this == null) {
					continue;
				}
				_this.sendMessage(message);
			}
		} catch (Exception e) {
			System.out.println("发送消息异常！");
			log.error("websocket发送消息异常  : 登录人ID = " + fuseridList.toString() + " , Exception = " + e.getMessage());
		}
	}

	/**
	 * 给所有在线用户发消息（单条）
	 * 
	 * @param message
	 *            消息
	 * @throws IOException
	 *             void
	 * @author hufx
	 * @date 2017年6月2日上午11:11:05
	 */
	public static void sendMessageAll(String message) {
		try {
			if (webSocketSet == null || webSocketSet.size() < 1 || StringUtils.isBlank(message)) {
				return;
			}
			for (WebSocketServer item : webSocketSet) {
				item.sendMessage(message);
			}
		} catch (IOException e) {
			System.out.println("发送消息异常！");
			log.error("websocket发送消息异常  : Exception = " + e.getMessage());
		}
	}

}