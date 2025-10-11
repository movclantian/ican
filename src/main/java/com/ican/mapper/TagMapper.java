package com.ican.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.model.entity.TagDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标签 Mapper
 * 
 * @author ican
 */
@Mapper
public interface TagMapper extends BaseMapper<TagDO> {
}
