package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The in-app notification centre: a person's own rows in their current workspace. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record NotificationView(UUID id, NotificationCategory category, String title, String body, UUID runId,
                                   UUID gateId, Instant createdAt, Instant readAt, boolean resolved) {
    }

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<NotificationView> list(@RequestParam(required = false) String filter,
                                       @RequestParam(defaultValue = "false") boolean unread,
                                       @RequestParam(defaultValue = "50") int limit) {
        var claims = claims();
        StringBuilder sql = new StringBuilder("select id, category, title, body, run_id, gate_id, created_at, read_at, "
                + "resolved_at from notification where user_id = ? and org_id = ?");
        List<Object> args = new ArrayList<>(List.of(claims.userId(), claims.orgId()));
        if (filter != null) {
            List<NotificationCategory> categories;
            try {
                categories = NotificationCategory.inFilter(filter);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown filter");
            }
            sql.append(" and category in (").append(String.join(",", categories.stream().map(c -> "?").toList())).append(")");
            categories.forEach(c -> args.add(c.name()));
        }
        if (unread) {
            sql.append(" and read_at is null");
        }
        sql.append(" order by created_at desc limit ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql.toString(), (rs, i) -> new NotificationView(rs.getObject("id", UUID.class),
                NotificationCategory.valueOf(rs.getString("category")), rs.getString("title"), rs.getString("body"),
                rs.getObject("run_id", UUID.class), rs.getObject("gate_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("read_at")),
                rs.getTimestamp("resolved_at") != null), args.toArray());
    }

    @GetMapping("/unread-count")
    public Map<String, Integer> unreadCount() {
        var claims = claims();
        Integer count = jdbc.queryForObject(
                "select count(*) from notification where user_id = ? and org_id = ? and read_at is null",
                Integer.class, claims.userId(), claims.orgId());
        return Map.of("count", count);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        var claims = claims();
        int updated = jdbc.update("update notification set read_at = coalesce(read_at, now()) "
                + "where id = ? and user_id = ? and org_id = ?", id, claims.userId(), claims.orgId());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        var claims = claims();
        jdbc.update("update notification set read_at = now() where user_id = ? and org_id = ? and read_at is null",
                claims.userId(), claims.orgId());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
