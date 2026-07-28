package org.springframework.samples.petclinic.customers.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.samples.petclinic.customers.model.PetType;

import java.util.Date;
import java.util.List;

record PetVisitsResponse(
    Integer id,

    String name,

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    Date birthDate,

    PetType type,

    List<VisitResponse> visits
) {
}
