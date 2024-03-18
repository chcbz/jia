package cn.jia.kefu.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.kefu.dao.KefuMessageDao;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.mapper.KefuMessageMapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Named
public class KefuMessageDaoImpl extends BaseDaoImpl<KefuMessageMapper, KefuMessageEntity> implements KefuMessageDao {

}
