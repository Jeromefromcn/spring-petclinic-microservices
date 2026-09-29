package org.springframework.samples.petclinic.visits.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LaneBuildHeaderFilterTest {

    @Test
    void marksTheResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LaneBuildHeaderFilter().doFilter(new MockHttpServletRequest("GET", "/pets/visits"), response, new MockFilterChain());
        assertThat(response.getHeader("X-Visits-Build")).isEqualTo("lane");
    }
}
