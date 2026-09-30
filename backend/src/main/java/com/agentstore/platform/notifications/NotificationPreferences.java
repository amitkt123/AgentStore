package com.agentstore.platform.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A person's channel choices per category and their quiet hours. Anything not saved falls back to the defaults. */
@Component
public class NotificationPreferences {

    public record Channels(boolean web, boolean email) {
    }

    public record QuietWindow(boolean enabled, LocalTime start, LocalTime end, ZoneId zone) {
    }

    private static final QuietWindow DEFAULT_QUIET =
            new QuietWindow(false, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("Asia/Kolkata"));

    private final JdbcTemplate jdbc;

    public NotificationPreferences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Channels channels(UUID userId, NotificationCategory category) {
        List<Channels> saved = jdbc.query("select web, email from notification_preference where user_id = ? and category = ?",
                (rs, i) -> new Channels(rs.getBoolean("web"), rs.getBoolean("email")), userId, category.name());
        return saved.isEmpty() ? new Channels(category.defaultWeb(), category.defaultEmail()) : saved.get(0);
    }

    public Map<NotificationCategory, Channels> all(UUID userId) {
        Map<NotificationCategory, Channels> result = new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : NotificationCategory.values()) {
            result.put(category, new Channels(category.defaultWeb(), category.defaultEmail()));
        }
        jdbc.query("select category, web, email from notification_preference where user_id = ?", rs -> {
            result.put(NotificationCategory.valueOf(rs.getString("category")),
                    new Channels(rs.getBoolean("web"), rs.getBoolean("email")));
        }, userId);
        return result;
    }

    public QuietWindow quiet(UUID userId) {
        List<QuietWindow> rows = jdbc.query(
                "select quiet_enabled, quiet_start, quiet_end, quiet_tz from app_user where id = ?",
                (rs, i) -> new QuietWindow(rs.getBoolean("quiet_enabled"), rs.getObject("quiet_start", LocalTime.class),
                        rs.getObject("quiet_end", LocalTime.class), ZoneId.of(rs.getString("quiet_tz"))), userId);
        return rows.isEmpty() ? DEFAULT_QUIET : rows.get(0);
    }

    @Transactional
    public void save(UUID userId, Map<NotificationCategory, Channels> changes, QuietWindow quietOrNull) {
        changes.forEach((category, channels) -> jdbc.update(
                "insert into notification_preference (user_id, category, web, email) values (?, ?, ?, ?) "
                        + "on conflict (user_id, category) do update set web = excluded.web, email = excluded.email",
                userId, category.name(), channels.web(), channels.email()));
        if (quietOrNull != null) {
            jdbc.update("update app_user set quiet_enabled = ?, quiet_start = ?, quiet_end = ?, quiet_tz = ? where id = ?",
                    quietOrNull.enabled(), quietOrNull.start(), quietOrNull.end(), quietOrNull.zone().getId(), userId);
        }
    }

    /** When this person's email for the category is due: null if email is off, otherwise now or the end of quiet hours. */
    public Instant emailDueAt(UUID userId, NotificationCategory category, Instant now) {
        if (!channels(userId, category).email()) {
            return null;
        }
        if (category == NotificationCategory.APPROVAL_NEEDED) {
            return now;
        }
        QuietWindow quiet = quiet(userId);
        return quiet.enabled() ? QuietHours.nextAllowed(now, quiet.start(), quiet.end(), quiet.zone()) : now;
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
