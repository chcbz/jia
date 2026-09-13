package cn.jia.user.mapper;

import cn.jia.user.dao.UserRelationRow;
import cn.jia.user.entity.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
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

    List<UserRelationRow> selectRelationsByUserIds(@Param("userIds") Collection<Long> userIds);

    UserEntity selectSecurityById(@Param("userId") long userId);

    List<UserEntity> selectSecurityByExactJiacn(@Param("jiacn") String jiacn);

    int incrementAuthEpoch(@Param("userId") long userId, @Param("expectedEpoch") long expectedEpoch);

    int incrementPoint(@Param("jiacn") String jiacn, @Param("add") int add,
                       @Param("updateTime") long updateTime);

    Integer selectPointByJiacn(@Param("jiacn") String jiacn);
}
