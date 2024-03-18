package cn.jia.mat.mapper;

import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.entity.MatMediaReqVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatMediaMapper extends BaseMapper<MatMediaEntity> {

    List<MatMediaEntity> selectByExample(MatMediaReqVO example);
}
