package cn.jia.core.util.thread;

import java.util.Map;

public abstract class ThreadRequestContent {
	public ThreadRequestContent() {}
	
	protected Map<String, Object> attr;
	
	public ThreadRequestContent(Map<String, Object> attr) {
		this.attr = attr;
	}

	/**
	 * 执行成功的动作。用户可以覆盖此方法
	 */
	public void onSuccess() {

	}

	/**
	 * 执行失败的动作。用户可以覆盖此方法
	 */
	public void onFailure() {

	}

	/**
	 * 用户必须实现这个抽象方法，告诉子线程要做什么
	 *
	 * @throws Exception 异常
	 */
	public abstract void doSomeThing() throws Exception;
}
