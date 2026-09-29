package org.springframework.samples.petclinic.visits.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Demo change for the lab's PR lane scenario (docker-gitops docs/demo/18):
 * marks every response from this build so a lane is visible at the client.
 * Lives only on the demo/pr-lane branch; its PR is opened and closed, never merged.
 */
@Component
class LaneBuildHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Visits-Build", "lane");
        chain.doFilter(request, response);
    }
}
