package cn.jia.agent.entity;

import lombok.Data;
import java.util.List;

@Data
public class AgentAbilitiesUpdateDTO {
    private List<String> expectedAbilities;
    private List<String> abilities;
}
