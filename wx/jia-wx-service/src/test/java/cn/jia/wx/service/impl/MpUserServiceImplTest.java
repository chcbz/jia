package cn.jia.wx.service.impl;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.util.ImgUtil;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.service.FileService;
import cn.jia.isp.service.LdapUserService;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.UserVO;
import cn.jia.user.service.UserService;
import cn.jia.wx.dao.MpUserDao;
import cn.jia.wx.entity.MpUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MpUserServiceImplTest extends BaseMockTest {
    @InjectMocks
    private MpUserServiceImpl mpUserService;

    @org.mockito.Mock
    private MpUserDao mpUserDao;

    @org.mockito.Mock
    private LdapUserService ldapUserService;

    @org.mockito.Mock
    private UserService userService;

    @org.mockito.Mock
    private FileService fileService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mpUserService, "baseDao", mpUserDao);
    }

    @Test
    void create() {
        try (MockedStatic<ImgUtil> theMock = mockStatic(ImgUtil.class);
             MockedStatic<SpringContextHolder> theMock2 = mockStatic(SpringContextHolder.class)) {
            theMock.when(() -> ImgUtil.fromUrl(anyString())).thenReturn(new byte[0]);
            theMock2.when(() -> SpringContextHolder.getProperty(anyString(), any())).thenReturn("path");
            when(ldapUserService.create(any())).thenReturn(new LdapUser());
            when(fileService.create(any())).thenReturn(new IspFileEntity());
            when(userService.create(any())).thenReturn(new UserVO());
            when(mpUserDao.insert(any())).thenReturn(1);
            MpUserEntity mpUserEntity = new MpUserEntity();
            mpUserEntity.setOpenId("openId");
            mpUserEntity.setEmail("email");
            mpUserEntity.setCountry("country");
            mpUserEntity.setProvince("province");
            mpUserEntity.setCity("city");
            mpUserEntity.setSex(1);
            mpUserEntity.setNickname("nickname");
            mpUserEntity.setHeadImgUrl("headImgUrl");
            MpUserEntity mpUserEntity1 = mpUserService.create(mpUserEntity);
            assertNotNull(mpUserEntity1);
        }
    }

    @Test
    void findByOpenId() {
        when(mpUserDao.selectByEntity(any())).thenReturn(Collections.singletonList(new MpUserEntity()));
        MpUserEntity mpUserEntity = mpUserService.findByOpenId("openId");
        assertNotNull(mpUserEntity);

        when(mpUserDao.selectByEntity(any())).thenReturn(Collections.emptyList());
        mpUserEntity = mpUserService.findByOpenId("openId");
        assertNull(mpUserEntity);
    }


    @Test
    void findByAppIdAndOpenIdUsesExactScopedLookupAndRejectsAmbiguity() {
        MpUserEntity expected = new MpUserEntity().setAppid("wx-app").setOpenId("OpenId");
        when(mpUserDao.selectByAppIdAndOpenIdExact("wx-app", "OpenId"))
                .thenReturn(List.of(expected));

        assertEquals(expected, mpUserService.findByAppIdAndOpenId("wx-app", "OpenId"));
        verify(mpUserDao).selectByAppIdAndOpenIdExact("wx-app", "OpenId");
        verify(mpUserDao, never()).selectByEntity(argThat(user ->
                "wx-app".equals(user.getAppid()) && "OpenId".equals(user.getOpenId())));

        when(mpUserDao.selectByAppIdAndOpenIdExact("wx-app", "duplicate"))
                .thenReturn(List.of(new MpUserEntity(), new MpUserEntity()));
        assertThrows(IllegalStateException.class,
                () -> mpUserService.findByAppIdAndOpenId("wx-app", "duplicate"));
        assertThrows(IllegalArgumentException.class,
                () -> mpUserService.findByAppIdAndOpenId("", "OpenId"));
    }

    @Test
    void findByJiacn() {
        when(mpUserDao.selectByEntity(any())).thenReturn(Collections.singletonList(new MpUserEntity()));
        MpUserEntity mpUserEntity = mpUserService.findByJiacn("jiacn");
        assertNotNull(mpUserEntity);

        when(mpUserDao.selectByEntity(any())).thenReturn(Collections.emptyList());
        mpUserEntity = mpUserService.findByJiacn("jiacn");
        assertNull(mpUserEntity);
    }

    @Test
    void sync() {
        when(mpUserDao.selectByEntity(any())).thenReturn(Collections.emptyList())
                .thenReturn(Collections.singletonList(new MpUserEntity()));
        try (MockedStatic<ImgUtil> theMock = mockStatic(ImgUtil.class);
             MockedStatic<SpringContextHolder> theMock2 = mockStatic(SpringContextHolder.class)) {
            theMock.when(() -> ImgUtil.fromUrl(anyString())).thenReturn(new byte[0]);
            theMock2.when(() -> SpringContextHolder.getProperty(anyString(), any())).thenReturn("path");
            when(ldapUserService.create(any())).thenReturn(new LdapUser());
            when(fileService.create(any())).thenReturn(new IspFileEntity());
            when(userService.create(any())).thenReturn(new UserVO());
            when(mpUserDao.insert(any())).thenReturn(1);
            MpUserEntity mpUserEntity = new MpUserEntity();
            mpUserEntity.setOpenId("openId1");
            mpUserEntity.setEmail("email");
            mpUserEntity.setCountry("country");
            mpUserEntity.setProvince("province");
            mpUserEntity.setCity("city");
            mpUserEntity.setSex(1);
            mpUserEntity.setNickname("nickname");
            mpUserEntity.setHeadImgUrl("headImgUrl");
            mpUserService.sync(List.of(mpUserEntity, new MpUserEntity()));
        }
    }

    @Test
    void unsubscribe() {
        when(mpUserDao.unsubscribe(any())).thenReturn(1);
        mpUserService.unsubscribe(new MpUserEntity());
    }
}