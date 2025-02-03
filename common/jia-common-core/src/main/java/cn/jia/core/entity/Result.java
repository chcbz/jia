package cn.jia.core.entity;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * Result : 响应的结果对象
 *
 * @author StarZou
 * @since 2014-09-27 16:28
 */
@Setter
@Getter
public class Result implements Serializable {
    @Serial
	private static final long serialVersionUID = 6288374846131788743L;

    /**
     * 信息
     */
    private String msg = "ok";
    /**
     * 是否成功
     */
    private String code = "E0";

    public Result() {

    }
}
