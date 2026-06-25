package com.etiya.replaylab;

import com.etiya.replaylab.config.ReplayLabProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ReplayLabProperties.class)
public class ReplayLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReplayLabApplication.class, args);
    }
}
