package org.springframework.samples.petclinic.customers.web;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

record VisitResponse(
    Integer id,

    @JsonFormat(pattern = "yyyy-MM-dd")
    Date date,

    String description,

    int petId
) {
}
