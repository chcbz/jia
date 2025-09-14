package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString(callSuper = true)
public class OrcheValueFunction extends OrcheValue {
    private String beanLabel;
    private String methodLabel;
    private String beanId;
    private String methodName;
    private List<?> parameters;
}