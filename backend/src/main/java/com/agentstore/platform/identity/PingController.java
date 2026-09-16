package com.agentstore.platform.identity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

    @GetMapping("/api/v1/ping")
    @PreAuthorize("hasRole('DEVELOPER')")
    String ping() {
        return "pong";
    }
}
