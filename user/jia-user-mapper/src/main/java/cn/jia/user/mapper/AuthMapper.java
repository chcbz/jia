package cn.jia.user.mapper;

import cn.jia.user.entity.AuthEntity;
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
public interface AuthMapper extends BaseMapper<AuthEntity> {

    List<AuthEntity> selectByUserId(@Param("userId") Long userId);
}
