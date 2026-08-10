package com.example.spring_docker_test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringDockerTestApplication {

    private static final Logger log = LoggerFactory.getLogger(SpringDockerTestApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(SpringDockerTestApplication.class, args);
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> localUrlLogger() {
        return event -> log.info("Dashboard ready: http://localhost:{}", event.getWebServer().getPort());
    }
}
