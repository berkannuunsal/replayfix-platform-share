package com.etiya.replayfix;

import com.etiya.replayfix.config.ReplayFixProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ReplayFixProperties.class)
public class ReplayFixApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReplayFixApplication.class, args);
    }
}
