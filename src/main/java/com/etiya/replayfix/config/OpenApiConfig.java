package com.etiya.replayfix.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI replayFixOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ReplayFix Platform API")
                        .description("Autonomous defect analysis and fixing platform")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("ReplayFix Innovation")
                                .email("innovation@replayfix.com")));
    }
}
