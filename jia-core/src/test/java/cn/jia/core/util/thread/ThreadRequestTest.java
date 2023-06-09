package cn.jia.core.util.thread;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
class ThreadRequestTest {

    @Test
    void start() {
        new ThreadRequest(new AbstractThreadRequestContent() {
            @Override
            public void doSomeThing() {
                log.info("doSomething");
            }
            @Override
            public void onSuccess() {
                log.info("override onSuccess");
            }
        }).start();
        log.info("other thread");
    }
}