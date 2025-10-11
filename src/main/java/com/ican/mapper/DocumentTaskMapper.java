package com.ican.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.model.entity.DocumentTaskDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档任务 Mapper
 * 
 * @author ican
 */
@Mapper
public interface DocumentTaskMapper extends BaseMapper<DocumentTaskDO> {
}
