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
    /** Configured process scope is applied before cursor/limit; no HTTP context required. */
    java.util.List<KefuMsgSubscribeEntity> findScheduledRecipients(String typeCode, long afterId, int limit);

    /** Revalidate the frozen recipient at dispatch; cancellation or reassignment stops delivery. */
    KefuMsgSubscribeEntity findScheduledRecipient(String clientId, long id, String typeCode, String jiacn);
}
