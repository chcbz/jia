package cn.jia.core.util.thread;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadRequestTest {

    @Test
    void start() {
        new ThreadRequest(new AbstractThreadRequestContent() {
            @Override
            public void doSomeThing() {
                System.out.println("doSomething");
            }
            @Override
            public void onSuccess() {
                System.out.println("override onSuccess");
            }
        }).start();
        System.out.println("other thread");
    }
}