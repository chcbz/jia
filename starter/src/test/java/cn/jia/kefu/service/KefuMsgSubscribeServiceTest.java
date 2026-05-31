package cn.jia.kefu.service;

import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.test.BaseDbUnitTest;
import org.springframework.test.context.jdbc.Sql;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Slf4j
class KefuMsgSubscribeServiceTest extends BaseDbUnitTest {
    @Autowired
    private KefuMsgSubscribeService kefuMsgSubscribeService;

    @Test
    @Sql(scripts = "classpath:testObject/kefu/kefu_msg_subscribe_init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void listMsgSubscribe() {
        KefuMsgSubscribeEntity example = new KefuMsgSubscribeEntity();
        example.setClientId("jia_client");
        example.setTypeCode(KefuMsgTypeCode.GIFT_USAGE.getCode());
        assertFalse(kefuMsgSubscribeService.findList(example).isEmpty());
    }
}