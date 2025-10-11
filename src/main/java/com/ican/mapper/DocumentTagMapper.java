package com.ican.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.model.entity.DocumentTagDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档标签关联 Mapper
 * 
 * @author ican
 */
@Mapper
public interface DocumentTagMapper extends BaseMapper<DocumentTagDO> {
}
