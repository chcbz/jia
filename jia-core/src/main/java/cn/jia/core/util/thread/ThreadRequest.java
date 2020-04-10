package cn.jia.core.util.thread;

public class ThreadRequest {
	private ThreadRequestContent rc;// 请求主体

	public ThreadRequest(ThreadRequestContent rc) {
		this.rc = rc;
	}

	public void start() { // 开始请求
		final Thread t = new Thread(new Runnable() {
			public void run() {
				try {
					rc.doSomeThing();// 响应请求
				} catch (Exception e) {
					e.printStackTrace();
					rc.onFailure(); // 如果执行失败
				}
				rc.onSuccess();// 如果执行成功
			}
		});
		t.start();
	}

	public static void main(String[] args) {
		new ThreadRequest(new ThreadRequestContent() {
			public void doSomeThing() {
				System.out.println("doSomething");
			}
			public void onSuccess() {
				System.out.println("override onSuccess");
			}
		}).start();
		System.out.println("other thread");
	}
}