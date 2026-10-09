package com.campustrade.notification.rabbitmq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
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
    public DirectExchange notificationDeadLetterExchange(RabbitNotificationProperties properties) {
        return new DirectExchange(properties.getNotificationDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue notificationQueue(RabbitNotificationProperties properties) {
        return QueueBuilder.durable(properties.getNotificationQueue()).quorum()
                .withArgument("x-dead-letter-strategy", "at-least-once").withArgument("x-overflow", "reject-publish")
                .deadLetterExchange(properties.getNotificationDeadLetterExchange())
                .deadLetterRoutingKey(properties.getNotificationDeadLetterRoutingKey())
                .build();
    }

    @Bean
    public Queue notificationDeadLetterQueue(RabbitNotificationProperties properties) {
        return QueueBuilder.durable(properties.getNotificationDeadLetterQueue()).quorum().build();
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
    public Binding notificationDeadLetterBinding(
            DirectExchange notificationDeadLetterExchange,
            Queue notificationDeadLetterQueue,
            RabbitNotificationProperties properties
    ) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(notificationDeadLetterExchange)
                .with(properties.getNotificationDeadLetterRoutingKey());
    }

    @Bean
    public DirectExchange notificationRetryExchange(RabbitNotificationProperties properties) {
        return new DirectExchange(properties.getNotificationRetryExchange(), true, false);
    }

    @Bean
    public Queue notificationRetryQueue(RabbitNotificationProperties properties) {
        return QueueBuilder.durable(properties.getNotificationRetryQueue()).quorum()
                .withArgument("x-dead-letter-strategy", "at-least-once").withArgument("x-overflow", "reject-publish")
                .ttl(Math.max(100, properties.getConsumerRetryDelayMs()))
                .deadLetterExchange(properties.getNotificationExchange())
                .deadLetterRoutingKey(properties.getNotificationRoutingKey()).build();
    }

    @Bean
    public Binding notificationRetryBinding(DirectExchange notificationRetryExchange, Queue notificationRetryQueue,
                                           RabbitNotificationProperties properties) {
        return BindingBuilder.bind(notificationRetryQueue).to(notificationRetryExchange)
                .with(properties.getNotificationRetryRoutingKey());
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
        return new RabbitTemplateCustomizer() {
            @Override
            public void customize(RabbitTemplate rabbitTemplate) {
            rabbitTemplate.setMandatory(true);
            rabbitTemplate.setBeforePublishPostProcessors(message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            }
        };
    }
}
