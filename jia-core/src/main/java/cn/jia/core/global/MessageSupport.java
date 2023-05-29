package cn.jia.core.global;

/**
 * @author chc
 */
public class MessageSupport implements Messageable {
	private static final long serialVersionUID = -2723284476195850067L;
	private String defaultMessage = null;
	private String messageKey = null;
	private Object[] args = null;

	@Override
	public String getDefaultMessage() {
		return defaultMessage;
	}

	public void setDefaultMessage(String defaultMessage) {
		this.defaultMessage = defaultMessage;
	}

	@Override
	public String getMessageKey() {
		return messageKey;
	}

	public void setMessageKey(String messageKey) {
		this.messageKey = messageKey;
	}

	@Override
	public Object[] getArgs() {
		return args;
	}

	public void setArgs(Object[] args) {
		this.args = args;
	}

	@Override
	public boolean hasDefaultMessage() {
		return (this.defaultMessage != null);
	}
}
