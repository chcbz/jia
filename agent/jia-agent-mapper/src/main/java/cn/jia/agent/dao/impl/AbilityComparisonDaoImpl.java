package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AbilityComparisonDao;
import cn.jia.agent.entity.AbilityComparisonEntity;
import cn.jia.agent.mapper.AbilityComparisonMapper;
import cn.jia.common.dao.BaseDaoImpl;
import jakarta.inject.Named;

@Named
public class AbilityComparisonDaoImpl extends BaseDaoImpl<AbilityComparisonMapper, AbilityComparisonEntity>
        implements AbilityComparisonDao {
}
