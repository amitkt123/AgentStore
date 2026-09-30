package com.agentstore.platform.notifications;

import com.agentstore.platform.execution.GateDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints behind the link in an approval email. The token is the credential; there is no login. */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    public record DecisionBody(@NotNull GateDecision decision, String note) {
    }

    private final ApprovalLinkService links;

    public ApprovalController(ApprovalLinkService links) {
        this.links = links;
    }

    /** Shows the card. Never changes state, so a mail scanner or link preview cannot approve anything. */
    @GetMapping("/{token}")
    public ApprovalLinkService.Card card(@PathVariable String token) {
        return links.card(token);
    }

    @PostMapping("/{token}/decision")
    public ApprovalLinkService.Outcome decide(@PathVariable String token, @Valid @RequestBody DecisionBody body,
                                              HttpServletRequest request) {
        return links.decide(token, body.decision() == GateDecision.APPROVE, body.note(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }
}
