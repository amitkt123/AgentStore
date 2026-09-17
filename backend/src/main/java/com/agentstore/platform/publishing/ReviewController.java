package com.agentstore.platform.publishing;

import com.agentstore.platform.identity.JwtService;
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

    public ReviewController(ReviewItemRepository reviewItemRepository, PublishingService publishingService) {
        this.reviewItemRepository = reviewItemRepository;
        this.publishingService = publishingService;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ReviewItemResponse> queue() {
        return reviewItemRepository.findByStatus(ReviewStatus.PENDING).stream()
                .map(ReviewItemResponse::from).toList();
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
