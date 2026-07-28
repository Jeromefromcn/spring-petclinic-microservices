package org.springframework.samples.petclinic.customers.web;

import java.util.List;

record OwnerVisitsResponse(
    Integer id,
    String firstName,
    String lastName,
    String address,
    String city,
    String telephone,
    List<PetVisitsResponse> pets
) {
}
