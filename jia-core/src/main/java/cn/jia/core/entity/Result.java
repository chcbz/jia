package cn.jia.core.entity;

import java.io.Serializable;

/**
 * Result : 响应的结果对象
 *
 * @author StarZou
 * @since 2014-09-27 16:28
 */
public class Result implements Serializable {
    private static final long serialVersionUID = 6288374846131788743L;

    /**
     * 信息
     */
    private String msg = "ok";
    /**
     * 是否成功
     */
    private String code = "E0";

    public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Result() {

    }
}
