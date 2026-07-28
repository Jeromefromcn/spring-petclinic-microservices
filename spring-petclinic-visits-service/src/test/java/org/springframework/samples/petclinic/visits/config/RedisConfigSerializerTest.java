package org.springframework.samples.petclinic.visits.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.samples.petclinic.visits.model.Visit;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisConfigSerializerTest {

    @SuppressWarnings("unchecked")
    private final RedisSerializer<Object> serializer = (RedisSerializer<Object>)
        new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class)).getValueSerializer();

    @Test
    void roundTripsEmptyVisitList() {
        byte[] bytes = serializer.serialize(List.of());
        Object result = serializer.deserialize(bytes);
        assertThat(result).isEqualTo(List.of());
    }

    @Test
    void roundTripsNonEmptyVisitList() {
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(7).date(new Date(0)).description("checkup").build();
        byte[] bytes = serializer.serialize(List.of(visit));
        Object result = serializer.deserialize(bytes);

        assertThat(result).isInstanceOf(List.class);
        List<?> visits = (List<?>) result;
        assertThat(visits).hasSize(1);
        assertThat(((Visit) visits.get(0)).getPetId()).isEqualTo(7);
        assertThat(((Visit) visits.get(0)).getDescription()).isEqualTo("checkup");
    }
}
