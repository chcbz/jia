package cn.jia.core.exception;

public class ValidationException extends EsException{
	private static final long serialVersionUID = 1L;
	
	public ValidationException() {
	}

	public ValidationException(String paramString) {
		super(paramString);
	}

	public ValidationException(String paramString, Object[] paramArrayOfObject) {
		super(paramString, paramArrayOfObject);
	}

	public ValidationException(String paramString, Throwable paramThrowable) {
		super(paramString, paramThrowable);
	}

	public ValidationException(String paramString, Throwable paramThrowable,
			Object[] paramArrayOfObject) {
		super(paramString, paramThrowable, paramArrayOfObject);
	}

	public ValidationException(Throwable paramThrowable) {
		super(paramThrowable);
	}
}
