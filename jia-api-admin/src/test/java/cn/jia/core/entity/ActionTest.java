package cn.jia.core.entity;

import org.junit.Test;

public class ActionTest {

    @Test
    public void hashCode1() {
        Action a1 = new Action();
        a1.setResourceId("jia-api-admin");
        a1.setModule("isp");
        a1.setFunc("app_install");

        Action a2 = new Action();
        a2.setResourceId("jia-api-admin");
        a2.setModule("isp");
        a2.setFunc("app_install");

        System.out.println(a1.equals(a2));
        System.out.println(a1.hashCode());
        System.out.println(a2.hashCode());
    }

    @Test
    public void equals1() {
    }
}