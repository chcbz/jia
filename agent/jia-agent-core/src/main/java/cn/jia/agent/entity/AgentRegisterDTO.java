package cn.jia.agent.entity;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentRegisterDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String name;
    private String avatar;
    @JsonSetter(nulls = Nulls.FAIL)
    private List<String> abilities;
    private String endpoint;
    private String personaCode;
    private String personaName;
}
