package cn.jia.chat.archive.maintenance.http;

import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes={ArchiveAdminController.class,ArchiveNativeController.class})
public class ArchiveMaintenanceExceptionHandler {
 @ExceptionHandler(ArchiveMaintenanceException.class)
 public ResponseEntity<JsonResult<?>> handle(ArchiveMaintenanceException e){JsonResult<Map<String,String>> result=JsonResult.failure(e.code(),e.getMessage());result.setStatus(e.status());if(!e.details().isEmpty())result.setData(e.details());return ResponseEntity.status(e.status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(result);}
}
