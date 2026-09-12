package cn.jia.mat.mapper;

import cn.jia.mat.entity.MatVoteItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatVoteItemMapper extends BaseMapper<MatVoteItemEntity> {

    void deleteByVoteId(Long voteId);

    int incrementNum(@Param("questionId") long questionId, @Param("opt") String opt);
}
