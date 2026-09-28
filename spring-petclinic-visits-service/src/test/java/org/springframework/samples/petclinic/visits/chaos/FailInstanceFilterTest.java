package org.springframework.samples.petclinic.visits.chaos;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FailInstanceFilterTest {

    private final ChaosToggles toggles = new ChaosToggles();

    private MockHttpServletResponse call(String instanceName, String uri, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new FailInstanceFilter(toggles, instanceName).doFilter(new MockHttpServletRequest("GET", uri), response, chain);
        return response;
    }

    @Test
    void failsThisInstanceWhenTheToggleNamesIt() throws Exception {
        toggles.set("fail-instance", "visits-service-abc");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call("visits-service-abc", "/owners/1", chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("chaos: fail-instance");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void servesNormallyWhenTheToggleNamesAnotherInstance() throws Exception {
        toggles.set("fail-instance", "visits-service-xyz");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call("visits-service-abc", "/owners/1", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void servesNormallyWhenTheToggleIsFalse() throws Exception {
        toggles.set("fail-instance", "false");
        MockFilterChain chain = new MockFilterChain();

        assertThat(call("visits-service-abc", "/owners/1", chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void emptyNamesNeverMatch() throws Exception {
        MockFilterChain chain = new MockFilterChain();   // toggle never set: value is ""

        assertThat(call("", "/owners/1", chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void actuatorIsNeverFailed() throws Exception {
        toggles.set("fail-instance", "visits-service-abc");
        MockFilterChain chain = new MockFilterChain();

        assertThat(call("visits-service-abc", "/actuator/health/readiness", chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
