package cn.jia.kefu.service;

import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.test.BaseDbUnitTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class KefuMsgServiceTest extends BaseDbUnitTest {
    @Autowired
    private KefuService kefuService;

    @Test
    @Sql(scripts = {
            "classpath:testObject/kefu/kefu_msg_type_init.sql",
            "classpath:testObject/wx/mp_info_init.sql",
            "classpath:testObject/wx/mp_template_init.sql",
            "classpath:testObject/wx/mp_user_init.sql",
            "classpath:testObject/kefu/kefu_msg_subscribe_init.sql"
    }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void sendMessage() throws Exception {
        String clientId = "jia_client";
        assertTrue(kefuService.sendMessage(KefuMsgTypeCode.VOTE, clientId, ""));
    }
}