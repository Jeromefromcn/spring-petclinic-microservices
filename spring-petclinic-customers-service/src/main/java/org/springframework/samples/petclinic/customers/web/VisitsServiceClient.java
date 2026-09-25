package org.springframework.samples.petclinic.customers.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static java.util.stream.Collectors.joining;

@Component
class VisitsServiceClient {

    private final RestTemplate restTemplate;
    private final ChaosToggles chaosToggles;
    private final String visitsUrl;

    VisitsServiceClient(RestTemplate restTemplate, ChaosToggles chaosToggles,
                        @Value("${petclinic.services.visits-url}") String visitsUrl) {
        this.restTemplate = restTemplate;
        this.chaosToggles = chaosToggles;
        this.visitsUrl = visitsUrl;
    }

    List<VisitResponse> getVisitsForPets(List<Integer> petIds) {
        if (petIds.isEmpty()) {
            return List.of();
        }

        if (chaosToggles.isEnabled("downstream-error")) {
            throw new DownstreamServiceException(
                "Simulated downstream error calling visits-service (chaos toggle 'downstream-error' enabled)");
        }

        try {
            VisitsWireResponse response = restTemplate.getForObject(
                visitsUrl + "/pets/visits?petId={petIds}", VisitsWireResponse.class, joinIds(petIds));
            return response == null ? List.of() : response.items();
        } catch (RestClientException e) {
            throw new DownstreamServiceException("Failed to fetch visits from visits-service", e);
        }
    }

    private String joinIds(List<Integer> petIds) {
        return petIds.stream().map(String::valueOf).collect(joining(","));
    }

    private record VisitsWireResponse(List<VisitResponse> items) {
    }
}
