package cn.jia.user.security;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserEntitySecurityBoundaryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ordinaryJsonInputCannotBindAccountSecurityFields() throws Exception {
        UserEntity user = objectMapper.readValue("""
                {"id":17,"nickname":"safe","accountState":"ACTIVE","authEpoch":0}
                """, UserEntity.class);

        assertNull(user.getAccountState());
        assertNull(user.getAuthEpoch());
    }

    @Test
    void mybatisPlusGenericWritesNeverIncludeAccountSecurityFields() throws Exception {
        assertNeverWritten("accountState");
        assertNeverWritten("authEpoch");
    }

    @Test
    void userGetMyListAndSearchResponseShapesDoNotExposeAccountSecurityFields() throws Exception {
        UserEntity entity = new UserEntity()
                .setId(17L)
                .setNickname("alice")
                .setAccountState("SUSPENDED")
                .setAuthEpoch(41L);
        UserVO view = new UserVO();
        view.setId(17L);
        view.setNickname("alice");
        view.setAccountState("DISABLED");
        view.setAuthEpoch(42L);

        assertHidden(objectMapper.writeValueAsString(entity));
        assertHidden(objectMapper.writeValueAsString(JsonResult.success(entity)));
        assertHidden(objectMapper.writeValueAsString(view));
        assertHidden(objectMapper.writeValueAsString(JsonResult.success(view)));
        assertHidden(objectMapper.writeValueAsString(new JsonResultPage<>(List.of(entity))));
        assertHidden(objectMapper.writeValueAsString(new JsonResultPage<>(List.of(view))));
    }

    private static void assertNeverWritten(String fieldName) throws Exception {
        TableField field = UserEntity.class.getDeclaredField(fieldName).getAnnotation(TableField.class);
        assertEquals(FieldStrategy.NEVER, field.insertStrategy());
        assertEquals(FieldStrategy.NEVER, field.updateStrategy());
    }

    private static void assertHidden(String json) {
        assertFalse(json.contains("accountState"), json);
        assertFalse(json.contains("authEpoch"), json);
    }
}
