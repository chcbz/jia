package cn.jia.core.util.thread;

/**
 * @author chc
 */
public class ThreadRequest {
	/**
	 * 请求主体
	 */
	private final AbstractThreadRequestContent rc;

	public ThreadRequest(AbstractThreadRequestContent rc) {
		this.rc = rc;
	}

	/**
	 * 开始请求
	 */
	public void start() {
		final Thread t = new Thread(() -> {
			try {
				// 响应请求
				rc.doSomeThing();
			} catch (Exception e) {
				e.printStackTrace();
				// 如果执行失败
				rc.onFailure();
			}
			// 如果执行成功
			rc.onSuccess();
		});
		t.start();
	}
}