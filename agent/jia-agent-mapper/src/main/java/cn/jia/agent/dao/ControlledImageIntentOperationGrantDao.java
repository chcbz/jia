package cn.jia.agent.dao;
import cn.jia.agent.entity.ControlledImageIntentOperationGrantEntity;
public interface ControlledImageIntentOperationGrantDao {
    ControlledImageIntentOperationGrantEntity findByIssueKey(String tenant,String client,String owner,String task,String key);
    ControlledImageIntentOperationGrantEntity lockByIssueKey(String tenant,String client,String owner,String task,String key);
    ControlledImageIntentOperationGrantEntity findByInteractionKey(String tenant,String client,String owner,String task,String key);
    ControlledImageIntentOperationGrantEntity findById(String tenant,String client,String owner,String task,String id);
    ControlledImageIntentOperationGrantEntity lockById(String tenant,String client,String owner,String task,String id);
    void insert(ControlledImageIntentOperationGrantEntity row);
    boolean reserve(ControlledImageIntentOperationGrantEntity row,long version);
    boolean consume(ControlledImageIntentOperationGrantEntity row,long version);
    boolean revoke(ControlledImageIntentOperationGrantEntity row,long version);
}
