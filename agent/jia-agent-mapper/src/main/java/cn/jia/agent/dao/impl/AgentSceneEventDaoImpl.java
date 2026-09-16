package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.mapper.AgentSceneEventMapper;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Named
public class AgentSceneEventDaoImpl implements AgentSceneEventDao {
 private final AgentSceneEventMapper baseMapper;
 @Inject public AgentSceneEventDaoImpl(AgentSceneEventMapper baseMapper){this.baseMapper=baseMapper;}
 @Override @Transactional(propagation=Propagation.MANDATORY) public void lockSceneVersionScope(String t,String c,String o,String s){requireScope(t,c,o,s);baseMapper.ensureAndLockVersion(t,c,o,s,DateUtil.nowTime());}
 @Override @Transactional(propagation=Propagation.MANDATORY) public long nextSceneVersion(String t,String c,String o,String s){requireScope(t,c,o,s);if(baseMapper.allocateNextVersion(t,c,o,s,DateUtil.nowTime())<=0)throw new IllegalStateException("Unable to allocate scoped scene version");Long n=baseMapper.selectLastAllocatedVersion();if(n==null||n<=0)throw new IllegalStateException("Unable to read allocated scoped scene version");return n;}
 @Override public Long findCurrentSceneVersion(String t,String c,String o,String s){requireScope(t,c,o,s);return baseMapper.selectCurrentVersion(t,c,o,s);}
 @Override public Long findLatestSceneVersion(String t,String c,String o,String s){requireScope(t,c,o,s);return baseMapper.selectLatestVersion(t,c,o,s);}
 @Override public Long findEarliestSceneVersion(String t,String c,String o,String s){requireScope(t,c,o,s);return baseMapper.selectEarliestVersion(t,c,o,s);}
 @Override public List<AgentSceneEventEntity> findAfterVersion(String t,String c,String o,String s,long v,int l){requireScope(t,c,o,s);return baseMapper.selectAfterVersion(t,c,o,s,v,Math.max(1,Math.min(l,1000)));}
 @Override public int insert(String t,String c,String o,String s,AgentSceneEventDTO event){requireScope(t,c,o,s);if(event==null||event.getSceneVersion()==null||StringUtil.isBlank(event.getEventType()))throw new IllegalArgumentException("scene event version and type are required");AgentSceneEventDTO e=safeCopy(event);AgentSceneEventEntity row=new AgentSceneEventEntity();row.setSceneVersion(e.getSceneVersion());row.setEventType(e.getEventType());row.setOccurredAt(e.getOccurredAt());row.setEventJson(serializeSafeEvent(e));row.setTenantId(t);row.setClientId(c);row.setOwnerJiacn(o);row.setSceneId(s);row.init4Creation();return baseMapper.insert(row);}
 private AgentSceneEventDTO safeCopy(AgentSceneEventDTO s){AgentSceneEventDTO e=new AgentSceneEventDTO();e.setSceneVersion(s.getSceneVersion());e.setEventType(s.getEventType());e.setOccurredAt(s.getOccurredAt());e.setState(AgentSceneStateDTO.copyOf(s.getState()));return e;}
 private String serializeSafeEvent(AgentSceneEventDTO e){String json=JsonUtil.toSafeJson(e);if(StringUtil.isBlank(json))throw new IllegalArgumentException("Unable to serialize safe scene event");return json;}
 private void requireScope(String t,String c,String o,String s){if(!"0".equals(t)||StringUtil.isBlank(c)||StringUtil.isBlank(o)||"0".equals(o)||StringUtil.isBlank(s))throw new IllegalArgumentException("strict scene owner scope is required");}
}
