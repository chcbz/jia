package cn.jia.user.mapper;

import cn.jia.user.entity.UserEntity;
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
public interface InfoMapper extends BaseMapper<UserEntity> {
    List<UserEntity> selectByRole(Long roleId);

    List<UserEntity> selectByGroup(Long groupId);

    List<UserEntity> selectByOrg(Long orgId);

    List<UserEntity> searchByExample(UserEntity user);

    UserEntity selectSecurityById(@Param("userId") long userId);

    List<UserEntity> selectSecurityByExactJiacn(@Param("jiacn") String jiacn);

    int incrementAuthEpoch(@Param("userId") long userId, @Param("expectedEpoch") long expectedEpoch);
}
