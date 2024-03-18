package cn.jia.mat.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class PhraseModel implements Serializable {

    private Long id;

    private String content;
}
