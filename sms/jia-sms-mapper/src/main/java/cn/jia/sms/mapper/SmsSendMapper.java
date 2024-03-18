package cn.jia.sms.mapper;

import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.entity.SmsSendVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
public interface SmsSendMapper extends BaseMapper<SmsSendEntity> {
    /**
     * 统计电话的发送数量
     * @param example 过滤条件
     * @return [{"mobile": "1345*", "num": 3}]
     */
    List<Map<String, Object>> groupByMobile(SmsSendVO example);
}
