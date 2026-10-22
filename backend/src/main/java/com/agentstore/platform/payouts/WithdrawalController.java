package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WithdrawalController implements PayoutApiController {

    private final WithdrawalService withdrawals;

    public WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    /** 201 with a new withdrawal, or 200 with the existing one when {@code requestId} was already used. */
    @PostMapping("/api/v1/developer/withdrawals")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ResponseEntity<WithdrawalView> request(@RequestBody WithdrawalRequest request) {
        if (request == null) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "request body is required");
        }
        var claims = claims();
        WithdrawalService.Requested r = withdrawals.requestOrExisting(claims.orgId(), claims.userId(),
                request.requestId(), request.amount());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.withdrawal());
    }

    @GetMapping("/api/v1/developer/withdrawals")
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<WithdrawalView> list(@RequestParam(defaultValue = "50") int limit) {
        return withdrawals.recent(claims().orgId(), limit);
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
