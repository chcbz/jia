package cn.jia.core.global;

import java.io.Serializable;

/**
 * @author chc
 */
public interface Messageable extends Serializable {
    /**
     * 获取默认消息
     *
     * @return 默认消息
     */
    String getDefaultMessage();

    /**
     * 是否有默认消息
     *
     * @return 是/否
     */
    boolean hasDefaultMessage();

    /**
     * 获取消息key
     *
     * @return 消息key
     */
    String getMessageKey();

    /**
     * 获取参数
     *
     * @return 参数
     */
    Object[] getArgs();
}
