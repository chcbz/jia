package cn.jia.kefu.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.kefu.dao.KefuMsgTypeDao;
import cn.jia.kefu.entity.KefuMsgTypeEntity;
import cn.jia.kefu.mapper.KefuMsgTypeMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-06-27
 */
@Named
public class KefuMsgTypeDaoImpl extends BaseDaoImpl<KefuMsgTypeMapper, KefuMsgTypeEntity> implements KefuMsgTypeDao {
    @Override
    public KefuMsgTypeEntity findMsgType(String typeCode) {
        Wrapper<KefuMsgTypeEntity> wrapper = Wrappers.lambdaQuery(new KefuMsgTypeEntity())
                .eq(KefuMsgTypeEntity::getTypeCode, typeCode);
        return baseMapper.selectOne(wrapper);
    }
}
