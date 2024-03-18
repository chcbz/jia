package cn.jia.core.util.thread;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

@Slf4j
class RunnableRequestTest {

    @Test
    void run() {
        Map<String, Object> m = new HashMap<>(1);
        m.put("attr1", "val1");
        new Thread(new RunnableRequest(new AbstractThreadRequestContent(m) {
            final String val = String.valueOf(attr.get("attr1"));
            @Override
            public void doSomeThing() {
                log.info("doSomething="+val);
            }
            @Override
            public void onSuccess() {
                log.info("override onSuccess");
            }
        })).start();
        log.info("other thread");
    }
}