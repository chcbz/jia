package cn.jia.mat.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class MatMediaResVO extends MatMediaEntity {
    private String link; //链接地址

    private String content; //内容
}
