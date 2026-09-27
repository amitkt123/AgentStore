package com.agentstore.platform.llm;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** The developer's LLM key store. JWT-only: {@code ApiKeyScope} keeps API keys away from it. */
@RestController
@RequestMapping("/api/v1/developer/llm-keys")
public class LlmKeyController {

    /** Whatever logs a request must not see the key. */
    public record PutRequest(String apiKey) {
        @Override
        public String toString() {
            return "PutRequest[apiKey=****]";
        }
    }

    private final ByokKeyService keys;

    public LlmKeyController(ByokKeyService keys) {
        this.keys = keys;
    }

    @PutMapping("/{provider}")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ByokKeyService.Summary put(@PathVariable String provider, @RequestBody PutRequest request) {
        return keys.put(claims().orgId(), vendor(provider), request.apiKey());
    }

    @GetMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<ByokKeyService.Summary> list() {
        return keys.list(claims().orgId());
    }

    @DeleteMapping("/{provider}")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String provider) {
        keys.delete(claims().orgId(), vendor(provider));
    }

    private static LlmVendor vendor(String provider) {
        return LlmVendor.fromWire(provider.toLowerCase(Locale.ROOT)).orElseThrow(() ->
                new LlmApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVIDER", "provider must be 'anthropic' or 'openai'"));
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
