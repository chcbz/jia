package cn.jia.dwz.dao;

import cn.jia.dwz.entity.DwzRecord;
import com.github.pagehelper.Page;

public interface DwzRecordMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(DwzRecord record);

    int insertSelective(DwzRecord record);

    DwzRecord selectByPrimaryKey(Integer id);

    DwzRecord selectByUri(String uri);

    int updateByPrimaryKeySelective(DwzRecord record);

    int updateByPrimaryKey(DwzRecord record);

    Page<DwzRecord> selectByExample(DwzRecord example);
}