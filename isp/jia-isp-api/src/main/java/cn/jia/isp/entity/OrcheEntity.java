package cn.jia.isp.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString(callSuper = true)
public class OrcheEntity {
    private String entityName;
    private List<OrcheEntity> joins;
    private String joinType;
    private String combineType;
    private List<OrcheOperation> operations;
    private List<String> groupBys;
    private List<OrcheResultSet> resultSets;
}