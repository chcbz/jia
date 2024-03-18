package cn.jia.sms.mapper;

import cn.jia.sms.entity.SmsConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
public interface SmsConfigMapper extends BaseMapper<SmsConfigEntity> {
    /**
     * 扣减剩余数量
     *
     * @param clientId 应用标识符
     * @return 更新记录数
     */
    int reduce(String clientId);
}
