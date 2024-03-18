package cn.jia.kefu.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.kefu.dao.KefuFaqDao;
import cn.jia.kefu.entity.KefuFaqEntity;
import cn.jia.kefu.mapper.KefuFaqMapper;
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
public class KefuFaqDaoImpl extends BaseDaoImpl<KefuFaqMapper, KefuFaqEntity> implements KefuFaqDao {

}
