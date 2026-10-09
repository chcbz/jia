package cn.jia.agent.dao;
import cn.jia.agent.entity.ControlledImageExecutionSourceV3Entity;
import java.util.List;
public interface ControlledImageExecutionSourceV3Dao {
    void insert(ControlledImageExecutionSourceV3Entity row);
    List<ControlledImageExecutionSourceV3Entity> list(String tenant,String client,String owner,String executionId);
}
