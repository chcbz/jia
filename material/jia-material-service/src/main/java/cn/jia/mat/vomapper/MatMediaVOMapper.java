package cn.jia.mat.vomapper;

import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.entity.MatMediaResVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MatMediaVOMapper {
    MatMediaVOMapper INSTANCE = Mappers.getMapper(MatMediaVOMapper.class);

    MatMediaResVO toVO(MatMediaEntity entity);
}
