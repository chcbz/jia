package cn.jia.agent.dao.impl;
import cn.jia.agent.dao.ControlledImageExecutionSourceV3Dao;
import cn.jia.agent.entity.ControlledImageExecutionSourceV3Entity;
import cn.jia.agent.mapper.ControlledImageExecutionSourceV3Mapper;
import jakarta.inject.*;
import java.util.*;
@Named public final class ControlledImageExecutionSourceV3DaoImpl implements ControlledImageExecutionSourceV3Dao {
 private final ControlledImageExecutionSourceV3Mapper m;
 @Inject public ControlledImageExecutionSourceV3DaoImpl(ControlledImageExecutionSourceV3Mapper m){this.m=Objects.requireNonNull(m);}
 public void insert(ControlledImageExecutionSourceV3Entity r){r.init4Creation();if(m.insert(r)!=1)throw new IllegalStateException("v3 source insert failed");}
 public List<ControlledImageExecutionSourceV3Entity> list(String t,String c,String o,String e){return List.copyOf(m.list(t,c,o,e));}
}
