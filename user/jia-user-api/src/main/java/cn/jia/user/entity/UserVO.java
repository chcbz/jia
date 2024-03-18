package cn.jia.user.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(name = "用户查询VO", description="")
public class UserVO extends UserEntity {
    private Long createTimeStart;
    private Long createTimeEnd;
    private Long updateTimeStart;
    private Long updateTimeEnd;
    private Long orgId;
    private Long roleId;
    private Long groupId;
    private List<String> jiacnList;
    private List<Long> groupIds;
    private List<Long> orgIds;
    private List<Long> roleIds;

    private String usernameLike;
    private String openidLike;
    private String jiacnLike;
    private String phoneLike;
    private String emailLike;
    private String nicknameLike;
    private String cityLike;
    private String countryLike;
    private String provinceLike;
    private String referrerLike;
}
