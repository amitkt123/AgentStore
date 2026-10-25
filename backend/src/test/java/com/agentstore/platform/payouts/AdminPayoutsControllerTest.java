package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPayoutsControllerTest extends WithdrawalTestSupport {

    private ResponseEntity<Map> post(Org as, UserRole role, String path, Object body) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, auth(as, role)), Map.class);
    }

    private ResponseEntity<Map> admin(String path, Object body) {
        return post(developer(false), UserRole.ADMIN, path, body);
    }

    private ResponseEntity<List> getList(UserRole role, String path) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(auth(developer(false), role)), List.class);
    }

    private HttpStatus getStatus(UserRole role, String path) {
        return HttpStatus.valueOf(rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(auth(developer(false), role)), String.class).getStatusCode().value());
    }

    private static void assertCode(ResponseEntity<Map> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody().get("code")).isEqualTo(code);
    }

    /** A verified developer whose bank account holder name differs from the legal name, so it fails NAME_MISMATCH. */
    private Org mismatchedDeveloper() {
        Org org = developer(true);
        jdbc.update("update developer_verification set legal_name = 'Someone Else' where org_id = ?", org.getId());
        assertCreated(addBank(org, "123456789012"));
        fund(org, "1000.00");
        return org;
    }

    private UUID activeAccountId(Org org) {
        return jdbc.queryForObject("select id from payout_account where org_id = ? and replaced_at is null",
                UUID.class, org.getId());
    }

    @Test
    void freezeBlocksWithdrawAndUnfreezeAllowsIt() {
        Org org = readyDeveloper("1000.00");

        var frozen = admin("/api/v1/admin/orgs/" + org.getId() + "/money/freeze", Map.of("reason", "chargeback review"));
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(frozen.getBody().get("moneyFrozenReason")).isEqualTo("chargeback review");
        Object firstAt = frozen.getBody().get("moneyFrozenAt");
        assertThat(firstAt).isNotNull();
        assertCode(withdraw(org, UUID.randomUUID(), "200.00"), HttpStatus.CONFLICT, "MONEY_FROZEN");

        // freezing again keeps the first time and reason
        var again = admin("/api/v1/admin/orgs/" + org.getId() + "/money/freeze", Map.of("reason", "another reason"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("moneyFrozenAt")).isEqualTo(firstAt);
        assertThat(again.getBody().get("moneyFrozenReason")).isEqualTo("chargeback review");

        var open = admin("/api/v1/admin/orgs/" + org.getId() + "/money/unfreeze", null);
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(open.getBody().get("moneyFrozenAt")).isNull();
        assertThat(open.getBody().get("moneyFrozenReason")).isNull();
        assertThat(withdraw(org, UUID.randomUUID(), "200.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void freezeNeedsReason() {
        Org org = readyDeveloper("1000.00");
        String path = "/api/v1/admin/orgs/" + org.getId() + "/money/freeze";
        assertCode(admin(path, Map.of("reason", "  ")), HttpStatus.BAD_REQUEST, "INVALID_FIELD");
        assertCode(admin(path, Map.of("reason", "x".repeat(501))), HttpStatus.BAD_REQUEST, "INVALID_FIELD");
        assertCode(admin(path, Map.of()), HttpStatus.BAD_REQUEST, "INVALID_FIELD");
        assertThat(jdbc.queryForObject("select money_frozen_at is null from org where id = ?", Boolean.class,
                org.getId())).isTrue();
    }

    @Test
    void missingBodyIsInvalidField() {
        Org org = readyDeveloper("1000.00");
        UUID accountId = activeAccountId(org);
        assertCode(admin("/api/v1/admin/orgs/" + org.getId() + "/money/freeze", null),
                HttpStatus.BAD_REQUEST, "INVALID_FIELD");
        assertCode(admin("/api/v1/admin/payout-accounts/" + accountId + "/approve", null),
                HttpStatus.BAD_REQUEST, "INVALID_FIELD");
    }

    @Test
    void nonAdminGets403OnEveryEndpoint() {
        Org org = readyDeveloper("1000.00");
        UUID anyId = UUID.randomUUID();
        for (UserRole role : List.of(UserRole.DEVELOPER, UserRole.BUYER_ADMIN)) {
            assertThat(post(org, role, "/api/v1/admin/orgs/" + org.getId() + "/money/freeze",
                    Map.of("reason", "x")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post(org, role, "/api/v1/admin/orgs/" + org.getId() + "/money/unfreeze", null)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post(org, role, "/api/v1/admin/payout-accounts/" + anyId + "/approve",
                    Map.of("note", "x")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(getStatus(role, "/api/v1/admin/withdrawals")).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(getStatus(role, "/api/v1/admin/payout-accounts")).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void approveNameMismatchVerifiesAndAllowsWithdraw() {
        Org org = mismatchedDeveloper();
        assertCode(withdraw(org, UUID.randomUUID(), "200.00"), HttpStatus.CONFLICT, "NO_VERIFIED_PAYOUT_ACCOUNT");
        UUID accountId = activeAccountId(org);

        List<Map> failed = getList(UserRole.ADMIN, "/api/v1/admin/payout-accounts?status=FAILED").getBody();
        Map row = failed.stream().filter(m -> accountId.toString().equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(row.get("orgId")).isEqualTo(org.getId().toString());
        assertThat(row.get("orgName")).isEqualTo(org.getName());
        assertThat(row.get("legalName")).isEqualTo("Someone Else");
        assertThat(row.get("failureReason")).isEqualTo("NAME_MISMATCH");
        assertThat(row).doesNotContainKey("accountNumber");

        var approved = admin("/api/v1/admin/payout-accounts/" + accountId + "/approve", Map.of("note", "checked passbook"));
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status")).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select approval_note from payout_account where id = ?", String.class, accountId))
                .isEqualTo("checked passbook");
        assertThat(jdbc.queryForObject("select approved_by_user_id is not null and verified_at is not null "
                + "from payout_account where id = ?", Boolean.class, accountId)).isTrue();
        assertThat(withdraw(org, UUID.randomUUID(), "200.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void approveVerifiedOrValidationFailedAccountIs409() {
        Org ok = readyDeveloper("1000.00");
        assertCode(admin("/api/v1/admin/payout-accounts/" + activeAccountId(ok) + "/approve", Map.of("note", "n")),
                HttpStatus.CONFLICT, "ACCOUNT_NOT_APPROVABLE");

        Org bad = mismatchedDeveloper();
        UUID id = activeAccountId(bad);
        jdbc.update("update payout_account set failure_reason = 'VALIDATION_FAILED' where id = ?", id);
        assertCode(admin("/api/v1/admin/payout-accounts/" + id + "/approve", Map.of("note", "n")),
                HttpStatus.CONFLICT, "ACCOUNT_NOT_APPROVABLE");

        // a name mismatch that was since replaced is not approvable either
        jdbc.update("update payout_account set failure_reason = 'NAME_MISMATCH', replaced_at = now() where id = ?", id);
        assertCode(admin("/api/v1/admin/payout-accounts/" + id + "/approve", Map.of("note", "n")),
                HttpStatus.CONFLICT, "ACCOUNT_NOT_APPROVABLE");

        jdbc.update("update payout_account set replaced_at = null where id = ?", id);
        assertCode(admin("/api/v1/admin/payout-accounts/" + id + "/approve", Map.of()),
                HttpStatus.BAD_REQUEST, "INVALID_FIELD");
    }

    @Test
    void withdrawalsFilterByStatusAndOrg() {
        Org a = readyDeveloper("1000.00");
        Org b = readyDeveloper("1000.00");
        UUID wa = id(withdraw(a, UUID.randomUUID(), "200.00"));
        UUID wb = id(withdraw(b, UUID.randomUUID(), "300.00"));
        jdbc.update("update withdrawal set status = 'PROCESSED' where id = ?", wb);

        List<Map> byOrg = getList(UserRole.ADMIN, "/api/v1/admin/withdrawals?orgId=" + a.getId()).getBody();
        assertThat(byOrg).extracting(m -> m.get("id")).containsExactly(wa.toString());
        assertThat(byOrg.get(0).get("orgName")).isEqualTo(a.getName());
        assertThat(byOrg.get(0).get("accountLabel")).isEqualTo("Bank ••9012");

        List<Map> processed = getList(UserRole.ADMIN,
                "/api/v1/admin/withdrawals?status=PROCESSED&orgId=" + b.getId()).getBody();
        assertThat(processed).extracting(m -> m.get("id")).containsExactly(wb.toString());
        assertThat(getList(UserRole.ADMIN, "/api/v1/admin/withdrawals?status=PROCESSING&orgId=" + b.getId()).getBody())
                .isEmpty();
        assertThat(getList(UserRole.ADMIN, "/api/v1/admin/withdrawals?limit=1").getBody()).hasSize(1);
    }
}
