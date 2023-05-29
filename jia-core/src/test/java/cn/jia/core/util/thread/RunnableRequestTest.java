package cn.jia.core.util.thread;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunnableRequestTest {

    @Test
    void run() {
        Map<String, Object> m = new HashMap<>(1);
        m.put("attr1", "val1");
        new Thread(new RunnableRequest(new AbstractThreadRequestContent(m) {
            final String val = String.valueOf(attr.get("attr1"));
            @Override
            public void doSomeThing() {
                System.out.println("doSomething="+val);
            }
            @Override
            public void onSuccess() {
                System.out.println("override onSuccess");
            }
        })).start();
        System.out.println("other thread");
    }
}