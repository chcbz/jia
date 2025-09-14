package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class OrcheValue {
    private String valueType;
    private String variableName;
    private String variableLabel;
}