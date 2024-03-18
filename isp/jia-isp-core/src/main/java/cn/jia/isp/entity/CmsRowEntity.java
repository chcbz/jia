package cn.jia.isp.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@Schema(name = "CmsRow对象")
public class CmsRowEntity {

    private String name;

    private String value;

}