package org.springframework.samples.petclinic.visits.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VisitCacheServiceTest {

    private final VisitRepository visitRepository = mock(VisitRepository.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private VisitCacheService cacheService;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        cacheService = new VisitCacheService(visitRepository, redisTemplate, chaosToggles, 1L);
    }

    @Test
    void queriesDatabaseAndPopulatesCacheOnMiss() {
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(111).build();
        given(valueOperations.get("visits:pet:111")).willReturn(null);
        given(visitRepository.findByPetIdIn(List.of(111))).willReturn(List.of(visit));

        List<Visit> visits = cacheService.findByPetIdIn(List.of(111));

        assertThat(visits).containsExactly(visit);
        verify(valueOperations).set("visits:pet:111", List.of(visit), Duration.ofSeconds(60));
    }

    @Test
    void returnsCachedVisitsWithoutQueryingDatabaseOnHit() {
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(111).build();
        given(valueOperations.get("visits:pet:111")).willReturn(List.of(visit));

        List<Visit> visits = cacheService.findByPetIdIn(List.of(111));

        assertThat(visits).containsExactly(visit);
        verify(visitRepository, never()).findByPetIdIn(any());
    }

    @Test
    void fallsBackToDatabaseWhenRedisReadFails() {
        given(valueOperations.get("visits:pet:111")).willThrow(new RedisConnectionFailureException("boom"));
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(111).build();
        given(visitRepository.findByPetIdIn(List.of(111))).willReturn(List.of(visit));

        List<Visit> visits = cacheService.findByPetIdIn(List.of(111));

        assertThat(visits).containsExactly(visit);
    }

    @Test
    void evictsCacheEntryForPet() {
        cacheService.evict(111);

        verify(redisTemplate).delete("visits:pet:111");
    }

    @Test
    void throwsSimulatedTimeoutWithoutTouchingRedisOrDatabaseWhenToggleEnabled() {
        chaosToggles.set("redis-timeout", true);

        assertThatThrownBy(() -> cacheService.findByPetIdIn(List.of(111)))
            .isInstanceOf(io.lettuce.core.RedisCommandTimeoutException.class);

        verify(visitRepository, never()).findByPetIdIn(any());
        verify(valueOperations, never()).get(any());
    }
}
