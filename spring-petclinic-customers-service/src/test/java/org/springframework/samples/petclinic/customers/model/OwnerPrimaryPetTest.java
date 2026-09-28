package org.springframework.samples.petclinic.customers.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerPrimaryPetTest {

    @Test
    void anOwnerWithOnePetShowsItsName() {
        Owner owner = new Owner();
        Pet pet = new Pet();
        pet.setName("Leo");
        owner.addPet(pet);

        assertThat(owner.getPrimaryPetName()).isEqualTo("Leo");
    }

    @Test
    void anOwnerWithNoPetsShowsNothing() {
        assertThat(new Owner().getPrimaryPetName()).isNull();
    }
}
