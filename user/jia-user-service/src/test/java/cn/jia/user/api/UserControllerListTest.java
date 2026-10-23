package cn.jia.user.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserRelationIds;
import cn.jia.user.entity.UserVO;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class UserControllerListTest {
    @Mock
    private UserService userService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        openMocks(this);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

}
