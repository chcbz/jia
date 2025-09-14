package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString(callSuper = true)
public class OrcheOperation {
    private String operationType;
    private OrcheValue left;
    private OrcheValue right;
    private String operator;
    private String combineType;
    private List<OrcheOperation> operations;
}
