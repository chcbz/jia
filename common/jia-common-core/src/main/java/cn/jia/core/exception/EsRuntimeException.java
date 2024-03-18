package cn.jia.core.exception;

import cn.jia.core.global.MessageSupport;
import cn.jia.core.global.Messageable;
import cn.jia.core.util.StringUtils;
import org.springframework.core.NestedRuntimeException;

import java.io.Serial;

/**
 * @author chc
 */
public class EsRuntimeException extends NestedRuntimeException implements Messageable {

	@Serial
	private static final long serialVersionUID = 2763605130168831962L;
	
	private static final String DEFAULT_MESSAGE_KEY = "E999";

	private final MessageSupport messageSupport = new MessageSupport();
	
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

	public EsRuntimeException(EsErrorConstants errorConstants, Object... args) {
		super(errorConstants.getMessage());
		if(StringUtils.isEmpty(errorConstants.getCode())){
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		}else{
			this.messageSupport.setMessageKey(errorConstants.getCode());
		}
		this.messageSupport.setArgs(args);
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
		if (StringUtils.isEmpty(errorCode)) {
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		} else {
			this.messageSupport.setMessageKey(errorCode);
		}
		this.messageSupport.setArgs(args);
	}
	
	public EsRuntimeException(String errorCode, Throwable paramThrowable) {
		super(paramThrowable.getMessage(), paramThrowable);
		if (StringUtils.isEmpty(errorCode)) {
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		} else {
			this.messageSupport.setMessageKey(errorCode);
		}
	}
	
	public EsRuntimeException(String errorCode, Throwable paramThrowable, Object[] args) {
		super(paramThrowable.getMessage(), paramThrowable);
		if (StringUtils.isEmpty(errorCode)) {
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		} else {
			this.messageSupport.setMessageKey(errorCode);
		}
		this.messageSupport.setArgs(args);
	}
	
	public EsRuntimeException(Throwable paramThrowable) {
		super(paramThrowable.getMessage(), paramThrowable);
		this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
	}

	@Override
	public boolean hasDefaultMessage() {
		return this.messageSupport.hasDefaultMessage();
	}

	public void setDefaultMessage(String paramString) {
		this.messageSupport.setDefaultMessage(paramString);
	}

	@Override
	public String getDefaultMessage() {
		return null;
	}

	@Override
	public String toString() {
		StringBuilder local = new StringBuilder(super.toString());
		if (this.messageSupport.getArgs() != null) {
			local.append(": ");
			Object[] arrayOfObject = this.messageSupport.getArgs();
			for (Object o : arrayOfObject) {
				local.append(o).append(" ");
			}
		}
		return local.toString();
	}

	@Override
	public String getMessageKey() {
		return this.messageSupport.getMessageKey();
	}

	@Override
	public Object[] getArgs() {
		return this.messageSupport.getArgs();
	}

	public void setArgs(Object[] paramArrayOfObject) {
		this.messageSupport.setArgs(paramArrayOfObject);
	}
	
}
