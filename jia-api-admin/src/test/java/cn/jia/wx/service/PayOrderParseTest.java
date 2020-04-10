package cn.jia.wx.service;

import org.junit.Test;

import static org.junit.Assert.*;

public class PayOrderParseTest {

    @Test
    public void scanPayNotifyResult() throws Exception {
//        Te t = Te.instance();
//        t.s();
        Demo demo = new Demo();
        demo.add1();
    }
}

abstract class Te {
    protected String ss;

    public static Te instance() {
        Te t = new Te1();
        t.ss = "2342";
        return t;
    }

    public abstract void s();
}

class Te1 extends Te {

    public void s() {
        String a = this.ss;
    }
}

class Demo {
    int j = 0;
    public void add() {
        j++;
    }
    public void add1() {
        for (int i = 1; i <= 10; i++) {
            add();
            System.out.print(j+"\t");
        }
    }
}