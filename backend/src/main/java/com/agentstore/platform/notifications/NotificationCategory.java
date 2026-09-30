package com.agentstore.platform.notifications;

import java.util.Arrays;
import java.util.List;

/** What a notification is about. The defaults are the channels on for a person who has changed nothing. */
public enum NotificationCategory {
    APPROVAL_NEEDED(true, true, "needs_you"),
    RUN_FINISHED(true, false, "agents"),
    RUN_STOPPED_EARLY(true, true, "agents"),
    SPEND_LIMITS(true, true, "money"),
    WEEKLY_SUMMARY(false, true, null);

    private final boolean defaultWeb;
    private final boolean defaultEmail;
    private final String filter;

    NotificationCategory(boolean defaultWeb, boolean defaultEmail, String filter) {
        this.defaultWeb = defaultWeb;
        this.defaultEmail = defaultEmail;
        this.filter = filter;
    }

    public boolean defaultWeb() {
        return defaultWeb;
    }

    public boolean defaultEmail() {
        return defaultEmail;
    }

    /** The centre tab this category belongs to, or null when it only shows unfiltered. */
    public String filter() {
        return filter;
    }

    public static List<NotificationCategory> inFilter(String filter) {
        List<NotificationCategory> matches = Arrays.stream(values()).filter(c -> filter.equals(c.filter)).toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown filter " + filter);
        }
        return matches;
    }
}
