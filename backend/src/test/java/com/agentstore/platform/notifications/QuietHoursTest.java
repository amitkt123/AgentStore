package com.agentstore.platform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuietHoursTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);
    private static final LocalTime EIGHT_AM = LocalTime.of(8, 0);

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void outsideTheWindowTheSendIsNotHeld() {
        Instant noon = at("2026-09-30T12:00:00Z");
        assertThat(QuietHours.nextAllowed(noon, EIGHT_PM, EIGHT_AM, UTC)).isEqualTo(noon);
    }

    @Test
    void inTheEveningPartOfAWrappingWindowItHoldsUntilNextMorning() {
        assertThat(QuietHours.nextAllowed(at("2026-09-30T21:00:00Z"), EIGHT_PM, EIGHT_AM, UTC))
                .isEqualTo(at("2026-10-01T08:00:00Z"));
    }

    @Test
    void inTheEarlyMorningPartOfAWrappingWindowItHoldsUntilThatMorning() {
        assertThat(QuietHours.nextAllowed(at("2026-09-30T03:00:00Z"), EIGHT_PM, EIGHT_AM, UTC))
                .isEqualTo(at("2026-09-30T08:00:00Z"));
    }

    @Test
    void theStartIsInsideAndTheEndIsOutside() {
        assertThat(QuietHours.nextAllowed(at("2026-09-30T20:00:00Z"), EIGHT_PM, EIGHT_AM, UTC))
                .isEqualTo(at("2026-10-01T08:00:00Z"));
        Instant end = at("2026-09-30T08:00:00Z");
        assertThat(QuietHours.nextAllowed(end, EIGHT_PM, EIGHT_AM, UTC)).isEqualTo(end);
    }

    @Test
    void aNonWrappingWindowHoldsInsideAndReleasesOutside() {
        LocalTime one = LocalTime.of(13, 0);
        LocalTime three = LocalTime.of(15, 0);
        assertThat(QuietHours.nextAllowed(at("2026-09-30T14:00:00Z"), one, three, UTC))
                .isEqualTo(at("2026-09-30T15:00:00Z"));
        Instant later = at("2026-09-30T16:00:00Z");
        assertThat(QuietHours.nextAllowed(later, one, three, UTC)).isEqualTo(later);
    }

    @Test
    void theWindowIsReadInThePersonsTimezone() {
        // 15:00Z is 20:30 in Kolkata (UTC+5:30), inside 20:00-08:00; the window ends 08:00 IST = 02:30Z next day.
        assertThat(QuietHours.nextAllowed(at("2026-09-30T15:00:00Z"), EIGHT_PM, EIGHT_AM, ZoneId.of("Asia/Kolkata")))
                .isEqualTo(at("2026-10-01T02:30:00Z"));
    }

    @Test
    void categoriesCarryTheirDefaultsAndFilters() {
        assertThat(NotificationCategory.APPROVAL_NEEDED.defaultWeb()).isTrue();
        assertThat(NotificationCategory.APPROVAL_NEEDED.defaultEmail()).isTrue();
        assertThat(NotificationCategory.RUN_FINISHED.defaultEmail()).isFalse();
        assertThat(NotificationCategory.RUN_STOPPED_EARLY.defaultEmail()).isTrue();
        assertThat(NotificationCategory.WEEKLY_SUMMARY.defaultWeb()).isFalse();
        assertThat(NotificationCategory.inFilter("needs_you")).containsExactly(NotificationCategory.APPROVAL_NEEDED);
        assertThat(NotificationCategory.inFilter("agents")).containsExactlyInAnyOrder(
                NotificationCategory.RUN_FINISHED, NotificationCategory.RUN_STOPPED_EARLY);
        assertThat(NotificationCategory.inFilter("money")).isEqualTo(List.of(NotificationCategory.SPEND_LIMITS));
        assertThatThrownBy(() -> NotificationCategory.inFilter("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
