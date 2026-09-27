package com.agentstore.platform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.SpringDocProviders;
import org.springdoc.core.service.AbstractRequestService;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.core.service.OperationService;
import org.springdoc.core.service.SecurityService;
import org.springdoc.core.utils.PropertyResolverUtils;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A second, independent OpenAPI document (served at /v3/api-docs/platform and /v3/api-docs.yaml/platform) for
 * WS6b's new /api/v1 endpoints, additive to the existing default /v3/api-docs document.
 *
 * <p>Built by hand rather than via springdoc's {@code GroupedOpenApi}/multi-group mechanism, for two independent
 * reasons found by decompiling springdoc 2.6.0 (reproduced identically on 2.8.9, the version this plan's Tech
 * Stack names — not a version-specific bug):
 * <ol>
 *   <li>{@code AbstractOpenApiResource.isPathToMatch} shows the app's top-level {@code springdoc.paths-to-match}
 *   (application.yml, restricting the default document to /runtime/v1/**, asserted by {@code RuntimeContractTest})
 *   is a GLOBAL filter applied unconditionally to every document, including named groups, whenever it is
 *   non-empty; a group's own {@code pathsToMatch}/{@code packagesToScan} is only ever consulted as a fallback when
 *   that global list is empty — and the bare/default endpoint can never itself be assigned a group name (springdoc
 *   rejects a null/blank group), so there is no config-only way to keep the default restricted while giving a
 *   second, named group a different restriction.</li>
 *   <li>{@code OpenAPIService} (built via the shared {@code ObjectFactory} springdoc normally injects) is an
 *   app-wide singleton with a mutable, unkeyed {@code openAPI}/{@code cachedOpenAPI} cache: once any other
 *   resource (the default one) has built and cached a document, a second resource sharing that SAME service
 *   instance gets back the SAME cached (already runtime-only) object instead of building its own — confirmed by
 *   this document rendering correctly when its own contract test runs alone, but reverting to the runtime-only
 *   set once {@code RuntimeContractTest} runs first in the same JVM/Spring context.</li>
 * </ol>
 *
 * <p>This resource is therefore a standalone {@code OpenApiWebMvcResource}, constructed with its OWN
 * {@code SpringDocConfigProperties} (pathsToMatch set to just these three endpoints) AND its OWN
 * {@code OpenAPIService} (so it never shares the default document's cache) — a fully independent object graph
 * from the default document's resource, built from otherwise-stateless, safely-shared helper services
 * (request/response/operation builders, security parsing, property resolution). No {@code GroupedOpenApi} bean
 * exists for "platform", so springdoc's multi-group machinery (and its own {@code /v3/api-docs/{group}} route) is
 * never activated, leaving these paths free for this controller.
 */
@RestController
class PlatformOpenApiConfig {

    private final OpenApiWebMvcResource resource;

    PlatformOpenApiConfig(AbstractRequestService requestBuilder, GenericResponseService responseBuilder,
                          OperationService operationParser, SpringDocConfigProperties sharedProperties,
                          SpringDocProviders springDocProviders, SpringDocCustomizers springDocCustomizers,
                          SecurityService securityService, PropertyResolverUtils propertyResolverUtils,
                          ApplicationContext applicationContext) {
        SpringDocConfigProperties platformProperties = new SpringDocConfigProperties();
        platformProperties.setApiDocs(sharedProperties.getApiDocs());
        platformProperties.setPathsToMatch(List.of("/api/v1/runs/*/flow", "/api/v1/agents/*/versions/*/readiness",
                "/api/v1/agents/*/versions/*/manifest/patches", "/api/v1/agents/*/sandbox-profiles/sync"));
        // A fixed relative server, matching RuntimeOpenApiConfig's runtimeOpenApi(): otherwise the URL is derived
        // from the request that happens to build the document, and the committed contract flaps with the port.
        OpenAPI skeleton = new OpenAPI().servers(List.of(new Server().url("/")));
        OpenAPIService platformOpenApiService = new OpenAPIService(Optional.of(skeleton), securityService, platformProperties,
                propertyResolverUtils, Optional.empty(), Optional.empty(), Optional.empty());
        platformOpenApiService.setApplicationContext(applicationContext);
        this.resource = new OpenApiWebMvcResource(() -> platformOpenApiService, requestBuilder, responseBuilder,
                operationParser, platformProperties, springDocProviders, springDocCustomizers);
    }

    @GetMapping(value = "/v3/api-docs/platform", produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] json(HttpServletRequest request, Locale locale) throws JsonProcessingException {
        return resource.openapiJson(request, "/v3/api-docs/platform", locale);
    }

    @GetMapping(value = "/v3/api-docs.yaml/platform", produces = "application/yaml")
    byte[] yaml(HttpServletRequest request, Locale locale) throws JsonProcessingException {
        return resource.openapiYaml(request, "/v3/api-docs.yaml/platform", locale);
    }
}
