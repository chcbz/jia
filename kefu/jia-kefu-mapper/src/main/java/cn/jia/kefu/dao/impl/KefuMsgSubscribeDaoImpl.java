package cn.jia.kefu.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.kefu.dao.KefuMsgSubscribeDao;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.mapper.KefuMsgSubscribeMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-02-18
 */
@Named
public class KefuMsgSubscribeDaoImpl extends BaseDaoImpl<KefuMsgSubscribeMapper, KefuMsgSubscribeEntity>
        implements KefuMsgSubscribeDao {
    @Override
    public KefuMsgSubscribeEntity findMsgSubscribe(String typeCode, String jiacn) {
        Wrapper<KefuMsgSubscribeEntity> wrapper = Wrappers.lambdaQuery(new KefuMsgSubscribeEntity())
                .eq(KefuMsgSubscribeEntity::getTypeCode, typeCode)
                .eq(KefuMsgSubscribeEntity::getJiacn, jiacn);
        return baseMapper.selectOne(wrapper);
    }
}
