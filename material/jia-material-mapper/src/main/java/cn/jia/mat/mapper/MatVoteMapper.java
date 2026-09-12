package cn.jia.mat.mapper;

import cn.jia.mat.entity.MatVoteEntity;
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
public interface MatVoteMapper extends BaseMapper<MatVoteEntity> {

    int incrementNum(@Param("voteId") long voteId);
}
