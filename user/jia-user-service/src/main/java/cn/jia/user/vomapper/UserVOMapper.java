package cn.jia.user.vomapper;

import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserVOMapper {
    UserVOMapper INSTANCE = Mappers.getMapper(UserVOMapper.class);

    UserVO toVO(UserEntity entity);
}
