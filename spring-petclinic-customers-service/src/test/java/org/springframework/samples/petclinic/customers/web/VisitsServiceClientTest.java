package org.springframework.samples.petclinic.customers.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VisitsServiceClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private MockRestServiceServer server;
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private VisitsServiceClient client;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new VisitsServiceClient(restTemplate, chaosToggles);
    }

    @Test
    void returnsEmptyListWithoutCallingVisitsServiceWhenNoPetIds() {
        List<VisitResponse> visits = client.getVisitsForPets(List.of());

        assertThat(visits).isEmpty();
        server.verify();
    }

    @Test
    void fetchesVisitsFromVisitsService() {
        server.expect(requestTo("http://visits-service/pets/visits?petId=111,222"))
            .andRespond(withSuccess(
                "{\"items\":[{\"id\":1,\"date\":\"2024-01-01\",\"description\":\"desc\",\"petId\":111}]}",
                MediaType.APPLICATION_JSON));

        List<VisitResponse> visits = client.getVisitsForPets(List.of(111, 222));

        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).petId()).isEqualTo(111);
        server.verify();
    }

    @Test
    void wrapsDownstreamFailureInDownstreamServiceException() {
        server.expect(requestTo("http://visits-service/pets/visits?petId=111"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.getVisitsForPets(List.of(111)))
            .isInstanceOf(DownstreamServiceException.class);
        server.verify();
    }

    @Test
    void throwsImmediatelyWithoutCallingVisitsServiceWhenDownstreamErrorToggleEnabled() {
        chaosToggles.set("downstream-error", true);

        assertThatThrownBy(() -> client.getVisitsForPets(List.of(111)))
            .isInstanceOf(DownstreamServiceException.class);
        server.verify();
    }
}
