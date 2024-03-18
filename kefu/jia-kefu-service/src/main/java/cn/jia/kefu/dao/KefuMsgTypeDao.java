package cn.jia.kefu.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.kefu.entity.KefuMsgTypeEntity;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-06-27
 */
public interface KefuMsgTypeDao extends IBaseDao<KefuMsgTypeEntity> {
    KefuMsgTypeEntity findMsgType(String typeCode);
}
