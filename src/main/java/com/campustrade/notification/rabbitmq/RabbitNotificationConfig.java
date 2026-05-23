package com.campustrade.notification.rabbitmq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("rabbitmq")
public class RabbitNotificationConfig {

    @Bean
    public DirectExchange notificationExchange(RabbitNotificationProperties properties) {
        return new DirectExchange(properties.getNotificationExchange(), true, false);
    }

    @Bean
    public Queue notificationQueue(RabbitNotificationProperties properties) {
        return new Queue(properties.getNotificationQueue(), true);
    }

    @Bean
    public Binding notificationBinding(
            DirectExchange notificationExchange,
            Queue notificationQueue,
            RabbitNotificationProperties properties
    ) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange)
                .with(properties.getNotificationRoutingKey());
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
