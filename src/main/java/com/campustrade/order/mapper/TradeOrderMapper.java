package com.campustrade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.order.model.TradeOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
}
