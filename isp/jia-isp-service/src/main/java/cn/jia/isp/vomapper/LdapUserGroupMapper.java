package cn.jia.isp.vomapper;

import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.entity.LdapUserGroupDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import javax.naming.Name;
import java.util.List;
import java.util.Set;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface LdapUserGroupMapper {
    LdapUserGroupMapper INSTANCE = Mappers.getMapper(LdapUserGroupMapper.class);

    @Mappings({
            @Mapping(target = "users", expression = "java(MapStruct.nameToString(entity.getMember()))")
    })
    LdapUserGroupDTO toVO(LdapUserGroup entity);

    List<LdapUserGroupDTO> toList(List<LdapUserGroup> list);

    class MapStruct {
        public static List<String> nameToString(Set<Name> member) {
            return member.stream().map(name -> String.valueOf(name).split("=")[1]).toList();
        }
    }
}
