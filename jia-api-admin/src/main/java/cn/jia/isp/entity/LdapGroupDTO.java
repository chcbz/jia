package cn.jia.isp.entity;

import lombok.Data;

@Data
public class LdapGroupDTO {

    private Integer serverId;

    private String cn;

    private String description;

    private Integer gidNumber;
}
