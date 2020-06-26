package cn.jia.core.socket;

import java.nio.channels.SelectionKey;

public interface SocketHandler {
	
	void handleMessage(SelectionKey key, String message) throws Exception;
	
	void sendMessage(SelectionKey key, String message) throws Exception;
}
