package org.springframework.samples.petclinic.visits.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.samples.petclinic.visits.cache.VisitCacheService;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


import static java.util.Arrays.asList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisitResource.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "chaos.slow-query-delay-ms=1")
class VisitResourceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VisitRepository visitRepository;

    @MockitoBean
    VisitCacheService visitCacheService;

    @MockitoBean
    ChaosToggles chaosToggles;

    @Test
    void shouldFetchVisits() throws Exception {
        given(visitCacheService.findByPetIdIn(asList(111, 222)))
            .willReturn(
                asList(
                    Visit.VisitBuilder.aVisit()
                        .id(1)
                        .petId(111)
                        .build(),
                    Visit.VisitBuilder.aVisit()
                        .id(2)
                        .petId(222)
                        .build(),
                    Visit.VisitBuilder.aVisit()
                        .id(3)
                        .petId(222)
                        .build()
                )
            );

        mvc.perform(get("/pets/visits?petId=111,222"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(1))
            .andExpect(jsonPath("$.items[1].id").value(2))
            .andExpect(jsonPath("$.items[2].id").value(3))
            .andExpect(jsonPath("$.items[0].petId").value(111))
            .andExpect(jsonPath("$.items[1].petId").value(222))
            .andExpect(jsonPath("$.items[2].petId").value(222));

        verify(chaosToggles).isEnabled("slow-query-enabled");
    }

    @Test
    void shouldStillFetchVisitsWhenSlowQueryToggleEnabled() throws Exception {
        given(chaosToggles.isEnabled("slow-query-enabled")).willReturn(true);
        given(visitCacheService.findByPetIdIn(asList(111))).willReturn(asList());

        mvc.perform(get("/pets/visits?petId=111"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldEvictCacheWhenVisitCreated() throws Exception {
        mvc.perform(post("/owners/5/pets/{petId}/visits", 111)
                .contentType("application/json")
                .content("{\"description\":\"a visit\"}"))
            .andExpect(status().isCreated());

        verify(visitCacheService).evict(111);
    }
}
