package org.springframework.samples.petclinic.visits.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.samples.petclinic.visits.model.Visit;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        // VisitCacheService only ever stores List<Visit> under its keys, so serialize against that
        // concrete type instead of GenericJacksonJsonRedisSerializer's unsafe default typing. Default
        // typing embeds a type id as the array's first element, which Jackson cannot round-trip for an
        // empty list (no element to hold the id) and throws MismatchedInputException on read. A
        // fixed-type serializer never needs a type id, so this class of bug can't happen.
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(
            TypeFactory.createDefaultInstance().constructCollectionType(List.class, Visit.class)));
        template.afterPropertiesSet();
        return template;
    }
}
