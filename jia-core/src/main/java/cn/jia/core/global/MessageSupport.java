package cn.jia.core.global;

public class MessageSupport implements Messageable {
	private static final long serialVersionUID = -2723284476195850067L;
	private String defaultMessage = null;
	private String messageKey = null;
	private Object[] args = null;

	public String getDefaultMessage() {
		return defaultMessage;
	}

	public void setDefaultMessage(String defaultMessage) {
		this.defaultMessage = defaultMessage;
	}

	public String getMessageKey() {
		return messageKey;
	}

	public void setMessageKey(String messageKey) {
		this.messageKey = messageKey;
	}

	public Object[] getArgs() {
		return args;
	}

	public void setArgs(Object[] args) {
		this.args = args;
	}

	public boolean hasDefaultMessage() {
		return (this.defaultMessage != null);
	}
}
