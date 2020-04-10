package cn.jia.core.socket;

import java.nio.channels.SelectionKey;

public interface SocketHandler {
	
	public void handleMessage(SelectionKey key, String message) throws Exception;
	
	public void sendMessage(SelectionKey key, String message) throws Exception;
}
