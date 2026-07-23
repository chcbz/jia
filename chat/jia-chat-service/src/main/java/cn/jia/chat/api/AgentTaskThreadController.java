package cn.jia.chat.api;

import cn.jia.chat.entity.AgentTaskThreadCreateDTO;
import cn.jia.chat.entity.AgentTaskThreadMessageCreateDTO;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.AgentTaskThreadService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/task-threads")
@RequiredArgsConstructor
public class AgentTaskThreadController {
    private final AgentTaskThreadService taskThreadService;

    @PostMapping("/{taskId}/team")
    public Object getOrCreateTeamThread(
            @PathVariable String taskId,
            @RequestBody(required = false) AgentTaskThreadCreateDTO request) {
        AgentTaskThreadCreateDTO safe = request == null ? new AgentTaskThreadCreateDTO() : request;
        Scope scope = currentScope();
        return JsonResult.success(taskThreadService.getOrCreateTeamThread(
                scope.tenantId(), scope.clientId(), taskId,
                safe.getActorAgentId(), safe.getTitle()));
    }

    @GetMapping("/{taskId}/team")
    public Object getTeamThread(
            @PathVariable String taskId,
            @RequestParam String actorAgentId) {
        Scope scope = currentScope();
        return JsonResult.success(taskThreadService.getTeamThread(
                scope.tenantId(), scope.clientId(), taskId, actorAgentId));
    }

    @PostMapping("/{taskId}/team/messages")
    public Object appendTeamMessage(
            @PathVariable String taskId,
            @RequestBody(required = false) AgentTaskThreadMessageCreateDTO request) {
        Scope scope = currentScope();
        return JsonResult.success(taskThreadService.appendTeamMessage(
                scope.tenantId(), scope.clientId(), taskId, request));
    }

    @GetMapping("/{taskId}/team/messages")
    public Object listTeamMessages(
            @PathVariable String taskId,
            @RequestParam String actorAgentId,
            @RequestParam(defaultValue = "100") int limit) {
        Scope scope = currentScope();
        return JsonResult.success(taskThreadService.listTeamMessages(
                scope.tenantId(), scope.clientId(), taskId, actorAgentId, limit));
    }

    @ExceptionHandler(AgentTaskThreadException.class)
    public ResponseEntity<JsonResult<Void>> handleTaskThreadException(
            AgentTaskThreadException exception) {
        return switch (exception.getReason()) {
            case INVALID_REQUEST -> error(
                    HttpStatus.BAD_REQUEST, "TASK_THREAD_INVALID", exception.getMessage());
            case NOT_FOUND_OR_FORBIDDEN -> error(
                    HttpStatus.NOT_FOUND, "TASK_THREAD_NOT_FOUND",
                    "Task thread is not available in the requested scope");
            case CONFLICT -> error(
                    HttpStatus.CONFLICT, "TASK_THREAD_CONFLICT", exception.getMessage());
            case PERSISTENCE_ERROR -> error(
                    HttpStatus.INTERNAL_SERVER_ERROR, "TASK_THREAD_ERROR",
                    "Task thread operation failed");
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> handleUnexpectedFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "TASK_THREAD_ERROR", "Task thread operation failed");
    }

    private Scope currentScope() {
        EsContext context = EsContextHolder.getContext();
        return new Scope(context.getJiacn(), context.getClientId());
    }

    private ResponseEntity<JsonResult<Void>> error(
            HttpStatus status, String code, String message) {
        JsonResult<Void> result = JsonResult.failure(code, message);
        result.setStatus(status.value());
        return ResponseEntity.status(status).body(result);
    }

    private record Scope(String tenantId, String clientId) {
    }
}
