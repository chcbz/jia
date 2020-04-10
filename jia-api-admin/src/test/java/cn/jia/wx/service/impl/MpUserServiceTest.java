package cn.jia.wx.service.impl;

import cn.jia.wx.entity.MpUser;
import cn.jia.wx.service.MpUserService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * @author chcbz
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MpUserServiceTest {

    @Autowired
    private MpUserService mpUserService;

    @Test
    public void sync() throws Exception {
        MpUser user = new MpUser();
        user.setOpenId("o-q51v0NKHWrGwyPMu9J5xdTTG7E");
        user.setProvince("广东");
        user.setHeadImgUrl("http://thirdwx.qlogo.cn/mmopen/gEfmHQXhW431u3jU1FXejn7ZoY7ZEMIoRvl7f2ibLW71t2EwReSBx44ia0YicPqqSeOXxM8PooXb1fJrVTkOfXD89HHEmy28uMf/132");
        List<MpUser> mpUserList = new ArrayList<>();
        mpUserList.add(user);
        mpUserService.sync(mpUserList);
    }
}