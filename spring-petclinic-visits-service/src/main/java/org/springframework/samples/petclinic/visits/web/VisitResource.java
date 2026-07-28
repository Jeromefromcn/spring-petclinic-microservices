/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.visits.web;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.visits.cache.VisitCacheService;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Michael Isvy
 * @author Maciej Szarlinski
 * @author Ramazan Sakin
 */
@RestController
@Timed("petclinic.visit")
class VisitResource {

    private static final Logger log = LoggerFactory.getLogger(VisitResource.class);
    private static final String SLOW_QUERY_TOGGLE = "slow-query-enabled";

    private final VisitRepository visitRepository;
    private final VisitCacheService visitCacheService;
    private final ChaosToggles chaosToggles;
    private final long slowQueryDelayMs;

    VisitResource(VisitRepository visitRepository, VisitCacheService visitCacheService, ChaosToggles chaosToggles,
                  @Value("${chaos.slow-query-delay-ms:3000}") long slowQueryDelayMs) {
        this.visitRepository = visitRepository;
        this.visitCacheService = visitCacheService;
        this.chaosToggles = chaosToggles;
        this.slowQueryDelayMs = slowQueryDelayMs;
    }

    @PostMapping("owners/*/pets/{petId}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public Visit create(
        @Valid @RequestBody Visit visit,
        @PathVariable("petId") @Min(1) int petId) {

        visit.setPetId(petId);
        log.info("Saving visit {}", visit);
        Visit saved = visitRepository.save(visit);
        visitCacheService.evict(petId);
        return saved;
    }

    @GetMapping("owners/*/pets/{petId}/visits")
    public List<Visit> read(@PathVariable("petId") @Min(1) int petId) {
        return visitRepository.findByPetId(petId);
    }

    @GetMapping("pets/visits")
    public Visits read(@RequestParam("petId") List<Integer> petIds) {
        simulateSlowQueryIfEnabled();
        final List<Visit> visits = visitCacheService.findByPetIdIn(petIds);
        return new Visits(visits);
    }

    private void simulateSlowQueryIfEnabled() {
        if (chaosToggles.isEnabled(SLOW_QUERY_TOGGLE)) {
            try {
                Thread.sleep(slowQueryDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Visits(
        List<Visit> items
    ) {
    }
}
