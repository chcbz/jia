package cn.jia.mat.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class MatNewsResVO extends MatNewsEntity {
    private String piclink; //链接地址

    private String bodylink;

    private String content; //内容
}
