package cn.jia.agent.dao.impl;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import cn.jia.agent.mapper.ControlledImageBridgeOperationMapper;
import jakarta.inject.Named;
import java.util.Objects;
@Named
public final class ControlledImageBridgeOperationDaoImpl implements ControlledImageBridgeOperationDao {
    private final ControlledImageBridgeOperationMapper mapper;
    public ControlledImageBridgeOperationDaoImpl(ControlledImageBridgeOperationMapper mapper){this.mapper=Objects.requireNonNull(mapper);}
    public ControlledImageBridgeOperationEntity find(String t,String c,String o,String task,String key){return mapper.select(t,c,o,task,key);}
    public ControlledImageBridgeOperationEntity lock(String t,String c,String o,String task,String key){return mapper.selectForUpdate(t,c,o,task,key);}
    public void insert(ControlledImageBridgeOperationEntity row){row.init4Creation();if(mapper.insert(row)!=1)throw new IllegalStateException("controlled bridge insert failed");}
}
