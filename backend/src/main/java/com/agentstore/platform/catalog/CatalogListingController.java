package com.agentstore.platform.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CatalogListingController {

    private final AgentVersionRepository agentVersionRepository;

    public CatalogListingController(AgentVersionRepository agentVersionRepository) {
        this.agentVersionRepository = agentVersionRepository;
    }

    @GetMapping("/api/v1/catalog")
    public List<CatalogListingResponse> list() {
        return agentVersionRepository.findByStatus(VersionStatus.LIVE).stream()
                .map(CatalogListingResponse::from)
                .toList();
    }
}
