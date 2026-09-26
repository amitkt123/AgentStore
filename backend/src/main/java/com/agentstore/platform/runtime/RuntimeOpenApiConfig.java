package com.agentstore.platform.runtime;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
class RuntimeOpenApiConfig {

    /** A fixed relative server so the generated document does not depend on the port a test happens to run on. */
    @Bean
    OpenAPI runtimeOpenApi() {
        return new OpenAPI()
                .info(new Info().title("AgentStore Agent Runtime API").version("1")
                        .description("The only interface agent code uses. Authenticate with the run token in AGENTSTORE_RUN_TOKEN."))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("runToken",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("runToken"));
    }
}
