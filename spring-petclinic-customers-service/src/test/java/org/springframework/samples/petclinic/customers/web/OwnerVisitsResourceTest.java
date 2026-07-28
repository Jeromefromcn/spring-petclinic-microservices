package org.springframework.samples.petclinic.customers.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.samples.petclinic.customers.model.Pet;
import org.springframework.samples.petclinic.customers.model.PetType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerVisitsResource.class)
@ActiveProfiles("test")
class OwnerVisitsResourceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OwnerRepository ownerRepository;

    @MockitoBean
    VisitsServiceClient visitsServiceClient;

    @Test
    void shouldReturnOwnerWithPetsAndVisits() throws Exception {
        Owner owner = new Owner();
        owner.setId(1);
        owner.setFirstName("George");
        owner.setLastName("Franklin");
        owner.setAddress("110 W. Liberty St.");
        owner.setCity("Madison");
        owner.setTelephone("6085551023");

        Pet pet = new Pet();
        pet.setId(2);
        pet.setName("Leo");
        PetType type = new PetType();
        type.setId(1);
        type.setName("cat");
        pet.setType(type);
        owner.addPet(pet);

        given(ownerRepository.findById(1)).willReturn(Optional.of(owner));
        given(visitsServiceClient.getVisitsForPets(List.of(2)))
            .willReturn(List.of(new VisitResponse(10, null, "checkup", 2)));

        mvc.perform(get("/owners/1/visits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("George"))
            .andExpect(jsonPath("$.pets[0].name").value("Leo"))
            .andExpect(jsonPath("$.pets[0].visits[0].description").value("checkup"));
    }

    @Test
    void shouldReturn404WhenOwnerNotFound() throws Exception {
        given(ownerRepository.findById(999)).willReturn(Optional.empty());

        mvc.perform(get("/owners/999/visits"))
            .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnEmptyPetsWithoutCallingVisitsServiceWhenOwnerHasNoPets() throws Exception {
        Owner owner = new Owner();
        owner.setId(3);
        owner.setFirstName("Jane");
        owner.setLastName("Doe");
        owner.setAddress("1 Main St.");
        owner.setCity("Springfield");
        owner.setTelephone("1234567890");

        given(ownerRepository.findById(3)).willReturn(Optional.of(owner));
        given(visitsServiceClient.getVisitsForPets(List.of())).willReturn(List.of());

        mvc.perform(get("/owners/3/visits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pets").isEmpty());
    }

    @Test
    void shouldReturn502WhenVisitsServiceFails() throws Exception {
        Owner owner = new Owner();
        owner.setId(4);
        owner.setFirstName("Alice");
        owner.setLastName("Smith");
        owner.setAddress("2 Main St.");
        owner.setCity("Shelbyville");
        owner.setTelephone("1234567890");

        Pet pet = new Pet();
        pet.setId(5);
        pet.setName("Rex");
        PetType type = new PetType();
        type.setId(1);
        type.setName("dog");
        pet.setType(type);
        owner.addPet(pet);

        given(ownerRepository.findById(4)).willReturn(Optional.of(owner));
        willThrow(new DownstreamServiceException("Simulated downstream error"))
            .given(visitsServiceClient).getVisitsForPets(List.of(5));

        mvc.perform(get("/owners/4/visits"))
            .andExpect(status().isBadGateway());
    }
}
