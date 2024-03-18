package cn.jia.mat.vomapper;

import cn.jia.mat.entity.MatVoteEntity;
import cn.jia.mat.entity.MatVoteResVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MatVoteVOMapper {
    MatVoteVOMapper INSTANCE = Mappers.getMapper(MatVoteVOMapper.class);

    MatVoteResVO toVO(MatVoteEntity entity);
}
