package com.ican.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.model.entity.DocumentVectorDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档向量ID映射 Mapper
 * 
 * @author 席崇援
 * @since 2024-10-08
 */
@Mapper
public interface DocumentVectorMapper extends BaseMapper<DocumentVectorDO> {
}
