package cn.jia.wx.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class MpInfoVO extends MpInfoEntity {
    private List<String> clientIdList;
}
