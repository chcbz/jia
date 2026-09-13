package cn.jia.core.redis;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisServiceCompareDeleteTest extends BaseMockTest {

    @Test
    void compareDeleteUsesOneAtomicLuaEvaluation() {
        RedisTemplate<String, String> template = mock(RedisTemplate.class);
        RedisService service = new RedisService();
        ReflectionTestUtils.setField(service, "redisTemplate", template);
        when(template.execute(any(RedisScript.class), eq(List.of("vote_user")), eq("337")))
                .thenReturn(1L);

        assertTrue(service.deleteIfValueEquals("vote_user", "337"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(template).execute(script.capture(), eq(List.of("vote_user")), eq("337"));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("redis.call('GET', KEYS[1]) == ARGV[1]"));
        assertTrue(lua.contains("redis.call('DEL', KEYS[1])"));
        verifyNoMoreInteractions(template);
    }

    @Test
    void compareDeleteReportsMismatchWithoutTreatingItAsFailure() {
        RedisTemplate<String, String> template = mock(RedisTemplate.class);
        RedisService service = new RedisService();
        ReflectionTestUtils.setField(service, "redisTemplate", template);
        when(template.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);

        assertFalse(service.deleteIfValueEquals("vote_user", "337"));
    }
}
