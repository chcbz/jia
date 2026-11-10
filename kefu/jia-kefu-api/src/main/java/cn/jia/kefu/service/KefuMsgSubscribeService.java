package cn.jia.kefu.service;

import cn.jia.common.service.IBaseService;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;

public interface KefuMsgSubscribeService extends IBaseService<KefuMsgSubscribeEntity> {
    /** Configured process scope is applied before cursor/limit; no HTTP context required. */
    java.util.List<KefuMsgSubscribeEntity> findScheduledRecipients(String typeCode, long afterId, int limit);

    /** Revalidate the frozen recipient at dispatch; cancellation or reassignment stops delivery. */
    KefuMsgSubscribeEntity findScheduledRecipient(String clientId, long id, String typeCode, String jiacn);
}
