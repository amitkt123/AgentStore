package com.agentstore.platform.notifications;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Quiet-window arithmetic. The window is {@code [start, end)} in the person's zone and may wrap midnight. */
public final class QuietHours {

    private QuietHours() {
    }

    /** {@code now} when it is outside the window; otherwise the next instant the window ends. */
    public static Instant nextAllowed(Instant now, LocalTime start, LocalTime end, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        LocalTime time = local.toLocalTime();
        boolean inside = start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
        if (!inside) {
            return now;
        }
        ZonedDateTime endToday = local.toLocalDate().atTime(end).atZone(zone);
        return (endToday.isAfter(local) ? endToday : endToday.plusDays(1)).toInstant();
    }
}
