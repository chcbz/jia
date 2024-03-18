package cn.jia.kefu.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.kefu.dao.KefuMsgLogDao;
import cn.jia.kefu.entity.KefuMsgLogEntity;
import cn.jia.kefu.mapper.KefuMsgLogMapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-06-01
 */
@Named
public class KefuMsgLogDaoImpl extends BaseDaoImpl<KefuMsgLogMapper, KefuMsgLogEntity> implements KefuMsgLogDao {

}
