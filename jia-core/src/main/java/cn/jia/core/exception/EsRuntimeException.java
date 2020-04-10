package cn.jia.core.exception;


import org.springframework.core.NestedCheckedException;

import cn.jia.core.global.MessageSupport;
import cn.jia.core.global.Messageable;
import cn.jia.core.util.StringUtils;

public class EsRuntimeException extends NestedCheckedException implements Messageable {

	private static final long serialVersionUID = 2763605130168831962L;
	
	private static final String DEFAULT_MESSAGE_KEY = "E999";

	private MessageSupport messageSupport = new MessageSupport();
	
	public EsRuntimeException(){
		super(DEFAULT_MESSAGE_KEY);
		this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
	}
	
	public EsRuntimeException(String errorCode) {
		super("");
		if(StringUtils.isEmpty(errorCode)){
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		}else{
			this.messageSupport.setMessageKey(errorCode);
		}
	}

	public EsRuntimeException(String errorCode, String msg) {
		super(msg);
		if(StringUtils.isEmpty(errorCode)){
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		}else{
			this.messageSupport.setMessageKey(errorCode);
		}
	}
	
	public EsRuntimeException(String errorCode, Object[] args) {
		super("");
		if (StringUtils.isEmpty(errorCode))
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		else
			this.messageSupport.setMessageKey(errorCode);
		this.messageSupport.setArgs(args);
	}
	
	public EsRuntimeException(String errorCode, Throwable paramThrowable) {
		super(paramThrowable.getMessage(), paramThrowable);
		if (StringUtils.isEmpty(errorCode))
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		else
			this.messageSupport.setMessageKey(errorCode);
	}
	
	public EsRuntimeException(String errorCode, Throwable paramThrowable, Object[] args) {
		super(paramThrowable.getMessage(), paramThrowable);
		if (StringUtils.isEmpty(errorCode))
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		else
			this.messageSupport.setMessageKey(errorCode);
		this.messageSupport.setArgs(args);
	}
	
	public EsRuntimeException(Throwable paramThrowable) {
		super(paramThrowable.getMessage(), paramThrowable);
		this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
	}
	public boolean hasDefaultMessage() {
		return this.messageSupport.hasDefaultMessage();
	}

	public void setDefaultMessage(String paramString) {
		this.messageSupport.setDefaultMessage(paramString);
	}


	public String getDefaultMessage() {
		return null;
	}

	public String toString() {
		StringBuilder local = new StringBuilder(super.toString());
		if (this.messageSupport.getArgs() != null) {
			local.append("  Message:");
			Object[] arrayOfObject = this.messageSupport.getArgs();
			for (Object o : arrayOfObject) local.append(o).append(" ");
		}
		return local.toString();
	}

	public String getMessageKey() {
		return this.messageSupport.getMessageKey();
	}

	public Object[] getArgs() {
		return this.messageSupport.getArgs();
	}

	public void setArgs(Object[] paramArrayOfObject) {
		this.messageSupport.setArgs(paramArrayOfObject);
	}
	
}
