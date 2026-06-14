package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskNoteDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String authorId;
    private String authorType;
    private String noteType;
    private String content;
    private Long createdAt;
}
