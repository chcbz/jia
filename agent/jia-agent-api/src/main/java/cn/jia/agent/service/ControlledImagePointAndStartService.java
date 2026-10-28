package cn.jia.agent.service;
import cn.jia.agent.entity.ControlledImagePointAndStartDTO;
public interface ControlledImagePointAndStartService {
    ControlledImagePointAndStartDTO.Receipt get(AgentTaskExecutionGrantService.Scope scope,String taskId,String assignmentKey);
    final class Failure extends RuntimeException { private final Reason reason; public Failure(Reason r){super(r.name());reason=r;} public Failure(Reason r,Throwable c){super(r.name(),c);reason=r;} public Reason reason(){return reason;} }
    enum Reason { BAD_REQUEST,NOT_FOUND,CONFLICT,SOURCE_UNAVAILABLE }
}
