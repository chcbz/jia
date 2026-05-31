package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskSearchDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String status;
    private String ability;
    private String keyword;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
