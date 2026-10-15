package cn.jia.chat.api;

import cn.jia.core.entity.JsonResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Old point/image/consent/INSPECT writes cannot be replayed into new skills. History stays GET-only. */
final class RetiredSkillWriteEndpoint {
    private RetiredSkillWriteEndpoint() { }
    static <T> ResponseEntity<JsonResult<T>> response() {
        JsonResult<T> result = JsonResult.failure("LEGACY_SKILL_WRITE_RETIRED", "Use the current task discussion skill contract");

        return ResponseEntity.status(HttpStatus.GONE).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }
}
