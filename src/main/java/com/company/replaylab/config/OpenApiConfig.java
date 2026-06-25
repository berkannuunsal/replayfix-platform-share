package com.company.replaylab.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI replayLabOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ReplayLab Platform API")
                        .description("AI-driven defect replay, remediation and validation platform")
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("ReplayLab Innovation")));
    }
}
