package org.springframework.samples.petclinic.visits.cache;

import io.lettuce.core.RedisCommandTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

@Component
public class VisitCacheService {

    private static final Logger log = LoggerFactory.getLogger(VisitCacheService.class);
    private static final String KEY_PREFIX = "visits:pet:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String REDIS_TIMEOUT_TOGGLE = "redis-timeout";

    private final VisitRepository visitRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ChaosToggles chaosToggles;
    private final long redisTimeoutDelayMs;

    public VisitCacheService(VisitRepository visitRepository, RedisTemplate<String, Object> redisTemplate,
                              ChaosToggles chaosToggles,
                              @Value("${chaos.redis-timeout-delay-ms:3000}") long redisTimeoutDelayMs) {
        this.visitRepository = visitRepository;
        this.redisTemplate = redisTemplate;
        this.chaosToggles = chaosToggles;
        this.redisTimeoutDelayMs = redisTimeoutDelayMs;
    }

    public List<Visit> findByPetIdIn(Collection<Integer> petIds) {
        if (chaosToggles.isEnabled(REDIS_TIMEOUT_TOGGLE)) {
            simulateRedisTimeout();
        }

        List<Visit> result = new ArrayList<>();
        List<Integer> misses = new ArrayList<>();

        for (Integer petId : petIds) {
            List<Visit> cached = getFromCache(petId);
            if (cached != null) {
                result.addAll(cached);
            } else {
                misses.add(petId);
            }
        }

        if (!misses.isEmpty()) {
            Map<Integer, List<Visit>> byPetId = visitRepository.findByPetIdIn(misses).stream()
                .collect(groupingBy(Visit::getPetId));
            for (Integer petId : misses) {
                List<Visit> visits = byPetId.getOrDefault(petId, List.of());
                putInCache(petId, visits);
                result.addAll(visits);
            }
        }

        return result;
    }

    public void evict(int petId) {
        try {
            redisTemplate.delete(KEY_PREFIX + petId);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, skipping cache eviction for pet {}", petId, e);
        }
    }

    private void simulateRedisTimeout() {
        try {
            Thread.sleep(redisTimeoutDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new RedisCommandTimeoutException(
            "Simulated Redis timeout (chaos toggle 'redis-timeout' enabled)");
    }

    @SuppressWarnings("unchecked")
    private List<Visit> getFromCache(int petId) {
        try {
            Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + petId);
            return (List<Visit>) cached;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, falling back to database for pet {}", petId, e);
            return null;
        }
    }

    private void putInCache(int petId, List<Visit> visits) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + petId, visits, TTL);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, skipping cache write for pet {}", petId, e);
        }
    }
}
