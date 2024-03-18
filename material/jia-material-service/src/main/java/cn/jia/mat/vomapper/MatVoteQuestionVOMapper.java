package cn.jia.mat.vomapper;

import cn.jia.mat.entity.MatVoteQuestionEntity;
import cn.jia.mat.entity.MatVoteQuestionVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MatVoteQuestionVOMapper {
    MatVoteQuestionVOMapper INSTANCE = Mappers.getMapper(MatVoteQuestionVOMapper.class);

    MatVoteQuestionVO toVO(MatVoteQuestionEntity entity);
}
