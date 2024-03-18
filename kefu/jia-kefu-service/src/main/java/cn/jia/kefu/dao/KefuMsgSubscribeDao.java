package cn.jia.kefu.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-02-18
 */
public interface KefuMsgSubscribeDao extends IBaseDao<KefuMsgSubscribeEntity> {
    KefuMsgSubscribeEntity findMsgSubscribe(String typeCode, String jiacn);
}
