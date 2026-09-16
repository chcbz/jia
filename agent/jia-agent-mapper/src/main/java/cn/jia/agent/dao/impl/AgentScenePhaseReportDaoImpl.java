package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentScenePhaseReportDao;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.mapper.AgentScenePhaseReportMapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Named
public class AgentScenePhaseReportDaoImpl implements AgentScenePhaseReportDao {
 private final AgentScenePhaseReportMapper baseMapper;
 @Inject public AgentScenePhaseReportDaoImpl(AgentScenePhaseReportMapper baseMapper){this.baseMapper=baseMapper;}
 @Override public AgentScenePhaseReportEntity findByReportId(String t,String c,String o,String s,String r){requireScope(t,c,o,s);if(StringUtil.isBlank(r))throw new IllegalArgumentException("reportId is required");return baseMapper.selectOne(scope(t,c,o,s).eq(AgentScenePhaseReportEntity::getReportId,r).last("limit 1"));}
 @Override @Transactional(propagation=Propagation.MANDATORY) public AgentScenePhaseReportEntity findByReportIdForUpdate(String t,String c,String o,String s,String r){requireScope(t,c,o,s);if(StringUtil.isBlank(r))throw new IllegalArgumentException("reportId is required");return baseMapper.selectScopedForUpdate(t,c,o,s,r);}
 @Override @Transactional(propagation=Propagation.MANDATORY) public boolean tryReserve(String t,String c,String o,String s,AgentScenePhaseReportEntity e){requireScope(t,c,o,s);requireReservation(e);e.setTenantId(t);e.setClientId(c);e.setOwnerJiacn(o);e.setSceneId(s);e.init4Creation();int n=baseMapper.reserveIgnore(e);if(n==1)return true;if(n==0)return false;throw new IllegalStateException("Phase report reservation affected an unexpected row count: "+n);}
 @Override @Transactional(propagation=Propagation.MANDATORY) public int finalizePendingResult(String t,String c,String o,String s,String r,String p,String f,long at){requireScope(t,c,o,s);if(StringUtil.isBlank(r)||StringUtil.isBlank(p)||StringUtil.isBlank(f)||at<0)throw new IllegalArgumentException("reportId, pendingResult, finalResult and nonnegative processedAt are required");AgentScenePhaseReportEntity u=new AgentScenePhaseReportEntity();u.setResult(f);u.setProcessedAt(at);u.setUpdateTime(at);return baseMapper.update(u,scope(t,c,o,s).eq(AgentScenePhaseReportEntity::getReportId,r).eq(AgentScenePhaseReportEntity::getResult,p));}
 private LambdaQueryWrapper<AgentScenePhaseReportEntity> scope(String t,String c,String o,String s){return new LambdaQueryWrapper<AgentScenePhaseReportEntity>().eq(AgentScenePhaseReportEntity::getTenantId,t).eq(AgentScenePhaseReportEntity::getClientId,c).eq(AgentScenePhaseReportEntity::getOwnerJiacn,o).eq(AgentScenePhaseReportEntity::getSceneId,s);}
 private void requireScope(String t,String c,String o,String s){if(!"0".equals(t)||StringUtil.isBlank(c)||StringUtil.isBlank(o)||"0".equals(o)||StringUtil.isBlank(s))throw new IllegalArgumentException("strict scene owner scope is required");}
 private void requireReservation(AgentScenePhaseReportEntity e){if(e==null)throw new IllegalArgumentException("phase report is required");for(String[] v:new String[][]{{"reportId",e.getReportId()},{"agentId",e.getAgentId()},{"phase",e.getPhase()},{"regionId",e.getRegionId()},{"result",e.getResult()}})if(StringUtil.isBlank(v[1]))throw new IllegalArgumentException(v[0]+" is required");if(e.getStateVersion()==null||e.getStateVersion()<=0)throw new IllegalArgumentException("stateVersion must be positive");if(e.getOccurredAt()==null||e.getOccurredAt()<0||e.getProcessedAt()==null||e.getProcessedAt()<0)throw new IllegalArgumentException("timestamps must be nonnegative");}
}
