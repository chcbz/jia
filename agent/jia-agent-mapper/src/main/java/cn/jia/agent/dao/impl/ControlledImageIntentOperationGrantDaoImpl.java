package cn.jia.agent.dao.impl;
import cn.jia.agent.dao.ControlledImageIntentOperationGrantDao;
import cn.jia.agent.entity.ControlledImageIntentOperationGrantEntity;
import cn.jia.agent.mapper.ControlledImageIntentOperationGrantMapper;
import jakarta.inject.*;
import java.util.Objects;
@Named public final class ControlledImageIntentOperationGrantDaoImpl implements ControlledImageIntentOperationGrantDao {
 private final ControlledImageIntentOperationGrantMapper m;
 @Inject public ControlledImageIntentOperationGrantDaoImpl(ControlledImageIntentOperationGrantMapper m){this.m=Objects.requireNonNull(m);}
 public ControlledImageIntentOperationGrantEntity findByIssueKey(String t,String c,String o,String task,String k){return m.byIssue(t,c,o,task,k);}
 public ControlledImageIntentOperationGrantEntity lockByIssueKey(String t,String c,String o,String task,String k){return m.lockIssue(t,c,o,task,k);}
 public ControlledImageIntentOperationGrantEntity findByInteractionKey(String t,String c,String o,String task,String k){return m.byInteraction(t,c,o,task,k);}
 public ControlledImageIntentOperationGrantEntity findById(String t,String c,String o,String task,String id){return m.byId(t,c,o,task,id);}
 public ControlledImageIntentOperationGrantEntity lockById(String t,String c,String o,String task,String id){return m.lockId(t,c,o,task,id);}
 public void insert(ControlledImageIntentOperationGrantEntity r){r.init4Creation();if(m.insert(r)!=1)throw new IllegalStateException("operation grant insert failed");}
 public boolean reserve(ControlledImageIntentOperationGrantEntity r,long v){return m.reserve(r,v)==1;}
 public boolean consume(ControlledImageIntentOperationGrantEntity r,long v){return m.consume(r,v)==1;}
 public boolean revoke(ControlledImageIntentOperationGrantEntity r,long v){return m.revoke(r,v)==1;}
}
