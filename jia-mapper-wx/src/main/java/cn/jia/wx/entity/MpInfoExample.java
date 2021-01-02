package cn.jia.wx.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class MpInfoExample extends MpInfo {
    private List<String> clientIdList;
}
