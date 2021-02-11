package cn.jia.core.util.thread;

import java.util.HashMap;
import java.util.Map;

public class RunnableRequest implements Runnable {
	
	private final ThreadRequestContent rc;// 请求主体

	public RunnableRequest(ThreadRequestContent rc) {
		this.rc = rc;
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		try {
			rc.doSomeThing();// 响应请求
		} catch (Exception e) {
			e.printStackTrace();
			rc.onFailure(); // 如果执行失败
		}
		rc.onSuccess();// 如果执行成功
	}
	
	public static void main(String[] args) {
		Map<String, Object> m = new HashMap<>();
		m.put("attr1", "val1");
		new Thread(new RunnableRequest(new ThreadRequestContent(m) {
			final String val = String.valueOf(attr.get("attr1"));
			public void doSomeThing() {
				System.out.println("doSomething="+val);
			}
			public void onSuccess() {
				System.out.println("override onSuccess");
			}
		})).start();
		System.out.println("other thread");
	}
}
