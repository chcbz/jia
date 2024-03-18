package cn.jia.kefu.service;

import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.entity.KefuMsgTypeEntity;

public interface KefuService {
    Boolean sendMessage(KefuMsgTypeCode msgType, String clientId, String... attr) throws Exception;

    void sendTemplate(KefuMsgTypeCode kefuMsgTypeCode, String jiacn, String... attr) throws Exception;

    void sendTemplate(KefuMsgTypeEntity kefuMsgType, KefuMsgSubscribeEntity item, String... attr) throws Exception;

    boolean sendWxTemplate(KefuMsgTypeEntity kefuMsgType, String jiacn, String... attr) throws Exception;
}
