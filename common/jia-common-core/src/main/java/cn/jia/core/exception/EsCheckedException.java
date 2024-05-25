package cn.jia.core.exception;

import org.springframework.core.NestedCheckedException;

import cn.jia.core.global.MessageSupport;
import cn.jia.core.global.Messageable;
import cn.jia.core.util.StringUtil;

import java.io.Serial;


/**
 * @author chc
 */
public class EsCheckedException extends NestedCheckedException implements Messageable {

	@Serial
	private static final long serialVersionUID = 2763605130168831962L;
	private static final String DEFAULT_MESSAGE_KEY = "E999";
	private final MessageSupport messageSupport = new MessageSupport();
	
	public EsCheckedException(){
		super(DEFAULT_MESSAGE_KEY);
		this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
	}
	
	public EsCheckedException(String errorCode) {
		super(errorCode);
		if(StringUtil.isEmpty(errorCode)){
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		}else{
			this.messageSupport.setMessageKey(errorCode);
		}
	}
	
	public EsCheckedException(String errorCode, Object[] args) {
		super(errorCode);
		if (StringUtil.isEmpty(errorCode)) {
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		} else {
			this.messageSupport.setMessageKey(errorCode);
		}
		this.messageSupport.setArgs(args);
	}
	
	public EsCheckedException(String errorCode, Throwable paramThrowable) {
		super(errorCode, paramThrowable);
		if (StringUtil.isEmpty(errorCode)) {
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		} else {
			this.messageSupport.setMessageKey(errorCode);
		}
	}
	
	public EsCheckedException(String errorCode, Throwable paramThrowable, Object[] args) {
		super(errorCode, paramThrowable);
		if (StringUtil.isEmpty(errorCode)) {
			this.messageSupport.setMessageKey(DEFAULT_MESSAGE_KEY);
		} else {
			this.messageSupport.setMessageKey(errorCode);
		}
		this.messageSupport.setArgs(args);
	}
	
	public EsCheckedException(Throwable paramThrowable) {
		super("", paramThrowable);
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
			local.append("  Message:");
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
