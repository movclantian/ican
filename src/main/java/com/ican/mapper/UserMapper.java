package com.ican.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 *
 * @author 席崇援
 * @since 2024-10-03
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
