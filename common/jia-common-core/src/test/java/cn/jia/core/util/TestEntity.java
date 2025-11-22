package cn.jia.core.util;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Setter
@Getter
public class TestEntity {
    private String name;
    private Integer age;
    private String email;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Duration duration;
    
    public TestEntity() {}
    
    public TestEntity(String name, Integer age, String email, Duration duration) {
        this.name = name;
        this.age = age;
        this.email = email;
        this.duration = duration;
    }

}