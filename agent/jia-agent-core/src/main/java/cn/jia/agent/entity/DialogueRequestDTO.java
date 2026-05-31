package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class DialogueRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String personaName;
    private String dialogueType;
}
