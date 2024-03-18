package cn.jia.core.util.thread;

/**
 * @author chc
 */
public class RunnableRequest implements Runnable {
	/**
	 * 请求主体
	 */
	private final AbstractThreadRequestContent rc;

	public RunnableRequest(AbstractThreadRequestContent rc) {
		this.rc = rc;
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
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
	}
}
