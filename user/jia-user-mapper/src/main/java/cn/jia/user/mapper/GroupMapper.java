package cn.jia.user.mapper;

import cn.jia.user.entity.GroupEntity;
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
public interface GroupMapper extends BaseMapper<GroupEntity> {
    List<GroupEntity> selectByUserId(@Param("userId") Long userId);

}
