package org.springframework.samples.petclinic.api.boundary.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class FallbackControllerTest {

    private final WebTestClient client = WebTestClient.bindToController(new FallbackController()).build();

    // The default CircuitBreaker filter forwards every route's failures here,
    // so GETs (most traffic) must get 503, not 405.
    @Test
    void getFallbackReturnsServiceUnavailable() {
        client.get().uri("/fallback").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void postFallbackReturnsServiceUnavailable() {
        client.post().uri("/fallback").exchange().expectStatus().isEqualTo(503);
    }
}
