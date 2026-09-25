package org.springframework.samples.petclinic.api.application;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.samples.petclinic.api.dto.OwnerDetails;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomersServiceClientTest {

    private MockWebServer server;
    private CustomersServiceClient client;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        client = new CustomersServiceClient(WebClient.builder(), baseUrl);
    }

    @AfterEach
    void shutdown() throws IOException {
        server.close();
    }

    @Test
    void getOwnerCallsConfiguredBaseUrl() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("{\"id\":7,\"firstName\":\"Jeff\",\"lastName\":\"Black\",\"address\":\"a\",\"city\":\"c\",\"telephone\":\"1\",\"pets\":[]}")
            .build());

        OwnerDetails owner = client.getOwner(7).block();

        RecordedRequest request = server.takeRequest();
        assertEquals("/owners/7", request.getPath());
        assertEquals(7, owner.id());
    }
}
