package com.campustrade.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.user.model.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
