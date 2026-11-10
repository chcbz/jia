package cn.jia.kefu.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.kefu.dao.KefuMsgSubscribeDao;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KefuMsgSubscribeServiceImpl extends BaseServiceImpl<KefuMsgSubscribeDao, KefuMsgSubscribeEntity> implements KefuMsgSubscribeService {
    @Override
    public java.util.List<KefuMsgSubscribeEntity> findScheduledRecipients(String typeCode, long afterId, int limit) {
        return baseDao.findScheduledRecipients(typeCode, afterId, limit);
    }

    @Override
    public KefuMsgSubscribeEntity findScheduledRecipient(String clientId, long id, String typeCode, String jiacn) {
        return baseDao.findScheduledRecipient(clientId, id, typeCode, jiacn);
    }
}
