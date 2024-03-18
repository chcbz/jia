package cn.jia.user.service.impl;

import cn.jia.test.BaseMockTest;
import cn.jia.user.common.UserConstants;
import cn.jia.user.dao.UserMsgDao;
import cn.jia.user.entity.MsgEntity;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MsgServiceImplTest extends BaseMockTest {
    @Mock
    UserMsgDao userMsgDao;
    @InjectMocks
    MsgServiceImpl msgServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(msgServiceImpl, "baseDao", userMsgDao);
    }

    @Test
    void testRecycle() {
        when(userMsgDao.updateById(any())).thenReturn(1);
        msgServiceImpl.recycle(1L);

        ArgumentCaptor<MsgEntity> captor = ArgumentCaptor.forClass(MsgEntity.class);
        verify(userMsgDao).updateById(captor.capture());
        Assertions.assertEquals(1, captor.getValue().getId());
        Assertions.assertEquals(UserConstants.MSG_STATUS_DELETE, captor.getValue().getStatus());
    }

    @Test
    void testRestore() {
        when(userMsgDao.updateById(any())).thenReturn(1);
        msgServiceImpl.restore(1L);

        ArgumentCaptor<MsgEntity> captor = ArgumentCaptor.forClass(MsgEntity.class);
        verify(userMsgDao).updateById(captor.capture());
        Assertions.assertEquals(1, captor.getValue().getId());
        Assertions.assertEquals(UserConstants.MSG_STATUS_READED, captor.getValue().getStatus());
    }

    @Test
    void testReadAll() {
        when(userMsgDao.updateByUserId(any())).thenReturn(1);
        msgServiceImpl.readAll(1L);

        ArgumentCaptor<MsgEntity> captor = ArgumentCaptor.forClass(MsgEntity.class);
        verify(userMsgDao).updateByUserId(captor.capture());
        Assertions.assertEquals(1, captor.getValue().getUserId());
        Assertions.assertEquals(UserConstants.MSG_STATUS_READED, captor.getValue().getStatus());
    }
}
