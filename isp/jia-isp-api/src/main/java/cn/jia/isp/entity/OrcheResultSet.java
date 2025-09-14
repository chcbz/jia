package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@ToString(callSuper = true)
public class OrcheResultSet {
    private String alias;
    private String valueType;
    private String variableName;
    private String variableLabel;
}