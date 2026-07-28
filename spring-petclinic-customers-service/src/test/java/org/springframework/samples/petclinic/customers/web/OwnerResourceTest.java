package org.springframework.samples.petclinic.customers.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.samples.petclinic.customers.web.mapper.OwnerEntityMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerResource.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "chaos.slow-query-delay-ms=1")
class OwnerResourceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OwnerRepository ownerRepository;

    @MockitoBean
    OwnerEntityMapper ownerEntityMapper;

    @MockitoBean
    ChaosToggles chaosToggles;

    @Test
    void shouldFetchOwnerAndCheckSlowQueryToggle() throws Exception {
        given(ownerRepository.findById(1)).willReturn(Optional.of(new Owner()));

        mvc.perform(get("/owners/1"))
            .andExpect(status().isOk());

        verify(chaosToggles).isEnabled("slow-query-enabled");
    }

    @Test
    void shouldStillFetchOwnerWhenSlowQueryToggleEnabled() throws Exception {
        given(chaosToggles.isEnabled("slow-query-enabled")).willReturn(true);
        given(ownerRepository.findById(1)).willReturn(Optional.of(new Owner()));

        mvc.perform(get("/owners/1"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldFetchAllOwnersAndCheckSlowQueryToggle() throws Exception {
        given(ownerRepository.findAll()).willReturn(List.of(new Owner()));

        mvc.perform(get("/owners"))
            .andExpect(status().isOk());

        verify(chaosToggles).isEnabled("slow-query-enabled");
    }
}
