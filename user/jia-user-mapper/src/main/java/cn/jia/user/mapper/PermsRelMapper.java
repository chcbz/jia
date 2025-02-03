package cn.jia.user.mapper;

import cn.jia.user.entity.PermsRelEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface PermsRelMapper extends BaseMapper<PermsRelEntity> {

    List<PermsRelEntity> selectByUserId(@Param("userId") Long userId);
}
