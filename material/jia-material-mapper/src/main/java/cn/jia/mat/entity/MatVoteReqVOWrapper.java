package cn.jia.mat.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class MatVoteReqVOWrapper implements BaseEntityWrapper<MatVoteReqVO, MatVoteEntity> {
    @Override
    public void appendQueryWrapper(MatVoteReqVO entity, QueryWrapper<MatVoteEntity> wrapper) {
        wrapper.lambda()
                .like(StringUtil.isNotEmpty(entity.getNameLike()), MatVoteEntity::getName, entity.getNameLike());
    }

    @Override
    public void appendUpdateWrapper(MatVoteReqVO entity, UpdateWrapper<MatVoteEntity> wrapper) {

    }
}
