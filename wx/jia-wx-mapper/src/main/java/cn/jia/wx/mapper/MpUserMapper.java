package cn.jia.wx.mapper;

import cn.jia.wx.entity.MpUserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-01-09
 */
public interface MpUserMapper extends BaseMapper<MpUserEntity> {

    List<MpUserEntity> selectByAppIdAndOpenIdExact(
            @Param("appid") String appid, @Param("openId") String openId);
}
