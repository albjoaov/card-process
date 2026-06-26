package com.cardprocess.cartao.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Cartao Service API",
        version = "1.0.0",
        description = "Card issuance core with SQS consumption and Redis-cached product integration"))
public class OpenApiConfig {
}
