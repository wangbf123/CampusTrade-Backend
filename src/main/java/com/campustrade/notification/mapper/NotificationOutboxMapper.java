package com.campustrade.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.notification.model.NotificationOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutboxEvent> {
}
