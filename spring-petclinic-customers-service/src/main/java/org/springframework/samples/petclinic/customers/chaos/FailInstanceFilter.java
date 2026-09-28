package org.springframework.samples.petclinic.customers.chaos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes exactly one instance misbehave: while chaos/customers-service/fail-instance
 * holds this pod's name, every business request gets 503. Actuator is exempt so
 * the pod stays Ready and alive - the point is an instance the platform still
 * routes to, which only outlier detection can take out.
 */
@Component
public class FailInstanceFilter extends OncePerRequestFilter {

    static final String TOGGLE = "fail-instance";

    private final ChaosToggles chaosToggles;
    private final String instanceName;

    public FailInstanceFilter(ChaosToggles chaosToggles, @Value("${HOSTNAME:}") String instanceName) {
        this.chaosToggles = chaosToggles;
        this.instanceName = instanceName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!instanceName.isEmpty() && instanceName.equals(chaosToggles.value(TOGGLE))) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("chaos: fail-instance");
            return;
        }
        chain.doFilter(request, response);
    }
}
