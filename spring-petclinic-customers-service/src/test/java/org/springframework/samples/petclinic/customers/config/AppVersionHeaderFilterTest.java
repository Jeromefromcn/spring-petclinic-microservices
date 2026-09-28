package org.springframework.samples.petclinic.customers.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.GitProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionHeaderFilterTest {

    private static ObjectProvider<GitProperties> provider(GitProperties git) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (git != null) {
            factory.addBean("gitProperties", git);
        }
        return factory.getBeanProvider(GitProperties.class);
    }

    private static MockHttpServletResponse run(AppVersionHeaderFilter filter) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/owners/1"), response, new MockFilterChain());
        return response;
    }

    @Test
    void stampsTheFirstTwelveCharactersOfTheCommitId() throws Exception {
        Properties props = new Properties();
        props.setProperty("commit.id", "0123456789abcdef0123456789abcdef01234567");

        MockHttpServletResponse response = run(new AppVersionHeaderFilter(provider(new GitProperties(props))));

        assertThat(response.getHeader("X-App-Version")).isEqualTo("0123456789ab");
    }

    @Test
    void omitsTheHeaderWhenTheBuildHasNoGitInfo() throws Exception {
        MockHttpServletResponse response = run(new AppVersionHeaderFilter(provider(null)));

        assertThat(response.getHeader("X-App-Version")).isNull();
    }
}
