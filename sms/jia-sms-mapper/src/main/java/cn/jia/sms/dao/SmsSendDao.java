package cn.jia.sms.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.entity.SmsSendVO;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
public interface SmsSendDao extends IBaseDao<SmsSendEntity> {
    /**
     * 统计电话的发送数量
     * @param example 过滤条件
     * @return [{"mobile": "1345*", "num": 3}]
     */
    List<Map<String, Object>> groupByMobile(SmsSendVO example);
}
