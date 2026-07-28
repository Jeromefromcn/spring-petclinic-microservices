package org.springframework.samples.petclinic.customers.chaos;

import com.ecwid.consul.v1.ConsulClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChaosConfig {

    @Bean
    ConsulClient chaosConsulClient(
            @Value("${spring.cloud.consul.host:localhost}") String consulHost,
            @Value("${spring.cloud.consul.port:8500}") int consulPort) {
        return new ConsulClient(consulHost, consulPort);
    }
}
