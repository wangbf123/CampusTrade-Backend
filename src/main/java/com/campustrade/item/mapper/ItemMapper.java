package com.campustrade.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.item.model.Item;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemMapper extends BaseMapper<Item> {
}
