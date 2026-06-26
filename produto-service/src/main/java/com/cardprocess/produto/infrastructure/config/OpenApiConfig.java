package com.cardprocess.produto.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Produto Service API",
        version = "1.0.0",
        description = "Catalog of card products with simple auditing"))
public class OpenApiConfig {
}
