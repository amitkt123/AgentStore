package com.agentstore.platform.notifications;

import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.GateService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public side of an approval link. Viewing never changes state. Deciding claims the link and decides the gate in
 * one transaction, as the link's own person, so every rule {@code GateService.decide} enforces (current approver,
 * still pending) applies exactly as in the web app, and a refused decision leaves the link unused.
 */
@Service
public class ApprovalLinkService {

    public record Card(String runDisplayId, String agentName, String action, String summary, Instant expiresAt) {
    }

    public record Outcome(String decision, String runDisplayId) {
    }

    private final JdbcTemplate jdbc;
    private final GateService gateService;

    public ApprovalLinkService(JdbcTemplate jdbc, GateService gateService) {
        this.jdbc = jdbc;
        this.gateService = gateService;
    }

    private record Link(UUID id, UUID userId, UUID gateId, boolean used, boolean expired) {
    }

    public Card card(String token) {
        Link link = find(token);
        var rows = jdbc.queryForList("select g.run_id, g.status, g.action, g.summary, g.expires_at, r.display_id, "
                + "a.name as agent_name from gate_request g join run r on r.id = g.run_id "
                + "join agent_version av on av.id = r.agent_version_id join agent a on a.id = av.agent_id "
                + "where g.id = ?", link.gateId());
        if (link.used() || link.expired() || rows.isEmpty() || !"PENDING".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link is no longer valid");
        }
        Map<String, Object> row = rows.get(0);
        return new Card(row.get("display_id").toString(), row.get("agent_name").toString(),
                row.get("action").toString(), row.get("summary").toString(),
                ((java.sql.Timestamp) row.get("expires_at")).toInstant());
    }

    @Transactional
    public Outcome decide(String token, boolean approve, String note, String ip, String userAgent) {
        Link link = find(token);
        int claimed = jdbc.update("update approval_link set used_at = now(), decided_ip = ?, decided_user_agent = ? "
                + "where id = ? and used_at is null and expires_at > now()", ip, userAgent, link.id());
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link is no longer valid");
        }
        UUID runId = jdbc.queryForObject("select run_id from gate_request where id = ?", UUID.class, link.gateId());
        try {
            gateService.decide(runId, link.gateId(), approve, link.userId(), note, DecisionChannel.EMAIL_LINK);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ResponseStatusException(HttpStatus.GONE, "This link is no longer valid");
            }
            throw e;   // including NotAnApproverException (403): the transaction rolls back and the link stays unused
        }
        String display = jdbc.queryForObject("select display_id from run where id = ?", String.class, runId);
        return new Outcome(approve ? "APPROVED" : "REJECTED", display);
    }

    private Link find(String token) {
        List<Link> found = jdbc.query("select id, user_id, gate_id, used_at is not null as used, expires_at <= now() as expired "
                        + "from approval_link where token_hash = ?",
                (rs, i) -> new Link(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("gate_id", UUID.class), rs.getBoolean("used"), rs.getBoolean("expired")),
                TokenHasher.sha256Hex(token));
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link");
        }
        return found.get(0);
    }
}
