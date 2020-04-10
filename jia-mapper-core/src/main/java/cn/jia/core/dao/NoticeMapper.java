package cn.jia.core.dao;

import cn.jia.core.entity.Notice;
import com.github.pagehelper.Page;

public interface NoticeMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Notice record);

    int insertSelective(Notice record);

    Notice selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Notice record);

    int updateByPrimaryKey(Notice record);

    Page<Notice> findByExamplePage(Notice record);
}