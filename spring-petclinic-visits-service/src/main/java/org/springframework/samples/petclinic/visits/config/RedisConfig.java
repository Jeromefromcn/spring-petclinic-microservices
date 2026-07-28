package org.springframework.samples.petclinic.visits.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        // Non-deprecated Jackson 3 based replacement for GenericJackson2JsonRedisSerializer (removed in a
        // future Spring Data Redis release). enableUnsafeDefaultTyping() reproduces the same behavior as
        // the old default constructor: embeds type info in the JSON payload so VisitCacheService can cast
        // cached values back to List<Visit> on read.
        template.setValueSerializer(GenericJacksonJsonRedisSerializer.builder().enableUnsafeDefaultTyping().build());
        template.afterPropertiesSet();
        return template;
    }
}
