package cn.jia.dwz.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.dwz.dao.DwzRecordDao;
import cn.jia.dwz.entity.DwzRecordEntity;
import cn.jia.dwz.mapper.DwzRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-21
 */
@Named
public class DwzRecordDaoImpl extends BaseDaoImpl<DwzRecordMapper, DwzRecordEntity> implements DwzRecordDao {

    @Override
    public DwzRecordEntity selectByUri(String uri) {
        DwzRecordEntity entity = new DwzRecordEntity();
        entity.setUri(uri);
        QueryWrapper<DwzRecordEntity> queryWrapper = new QueryWrapper<>(entity);
        return baseMapper.selectOne(queryWrapper);
    }
}
