package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(callSuper = true)
public class OrcheValueVariable extends OrcheValue {
    private String variableName;
    private String variableLabel;
}