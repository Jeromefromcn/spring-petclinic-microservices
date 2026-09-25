package org.springframework.samples.petclinic.api.boundary.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    // Target of the default CircuitBreaker filter for every route, so it must
    // accept any method: a GET forwarded to a POST-only mapping surfaced as 405.
    @RequestMapping("/fallback")
    public ResponseEntity<String> fallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Service is temporarily unavailable. Please try again later.");
    }
}
