package com.agentstore.platform.publishing;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.manifest.ManifestParser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review")
public class ReviewController {

    private final ReviewItemRepository reviewItemRepository;
    private final PublishingService publishingService;
    private final ManifestParser manifestParser;

    public ReviewController(ReviewItemRepository reviewItemRepository, PublishingService publishingService,
                             ManifestParser manifestParser) {
        this.reviewItemRepository = reviewItemRepository;
        this.publishingService = publishingService;
        this.manifestParser = manifestParser;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ReviewItemResponse> queue() {
        return reviewItemRepository.findByStatus(ReviewStatus.PENDING).stream()
                .map(ReviewItemResponse::from).toList();
    }

    @GetMapping("/{reviewItemId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewItemDetailResponse detail(@PathVariable UUID reviewItemId) {
        ReviewItem item = reviewItemRepository.findById(reviewItemId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Review item not found"));
        var manifest = manifestParser.parse(item.getAgentVersion().getManifestJson());
        return ReviewItemDetailResponse.from(item, manifest);
    }

    @PostMapping("/{reviewItemId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewItemResponse approve(@PathVariable UUID reviewItemId, @RequestBody ReviewDecisionRequest request) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ReviewItemResponse.from(publishingService.approve(reviewItemId, claims.userId(), request.notes()));
    }

    @PostMapping("/{reviewItemId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewItemResponse reject(@PathVariable UUID reviewItemId, @RequestBody ReviewDecisionRequest request) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ReviewItemResponse.from(publishingService.reject(reviewItemId, claims.userId(), request.notes()));
    }
}
