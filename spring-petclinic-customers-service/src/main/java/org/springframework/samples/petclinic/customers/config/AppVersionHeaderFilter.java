package org.springframework.samples.petclinic.customers.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps every response with the commit this build came from, so a caller -
 * and the mesh's access log - can tell which version answered. The value is
 * the first 12 characters of the commit id, which is also the image tag
 * lab-environment's build.sh gives the image.
 */
@Component
public class AppVersionHeaderFilter extends OncePerRequestFilter {

    static final String HEADER = "X-App-Version";

    private final String version;

    public AppVersionHeaderFilter(ObjectProvider<GitProperties> git) {
        GitProperties props = git.getIfAvailable();
        String id = props == null ? null : props.getCommitId();
        this.version = id == null ? null : id.substring(0, Math.min(12, id.length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (version != null) {
            response.setHeader(HEADER, version);
        }
        chain.doFilter(request, response);
    }
}
