package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferencesTest extends AbstractIntegrationTest {

    @Autowired NotificationPreferences preferences;
    @Autowired IdentityFixtures identities;

    private UUID newUser() {
        return identities.buyerAdmin("prefs-" + UUID.randomUUID() + "@example.com").getUser().getId();
    }

    @Test
    void aPersonWhoChangedNothingGetsTheDefaults() {
        UUID user = newUser();

        assertThat(preferences.channels(user, NotificationCategory.RUN_FINISHED))
                .isEqualTo(new NotificationPreferences.Channels(true, false));
        assertThat(preferences.channels(user, NotificationCategory.APPROVAL_NEEDED))
                .isEqualTo(new NotificationPreferences.Channels(true, true));
        assertThat(preferences.quiet(user)).isEqualTo(new NotificationPreferences.QuietWindow(
                false, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void savedChoicesOverrideTheDefaultsForThatCategoryOnly() {
        UUID user = newUser();

        preferences.save(user, Map.of(NotificationCategory.RUN_FINISHED, new NotificationPreferences.Channels(true, true)),
                null);

        assertThat(preferences.channels(user, NotificationCategory.RUN_FINISHED).email()).isTrue();
        assertThat(preferences.channels(user, NotificationCategory.RUN_STOPPED_EARLY).email()).isTrue();
        assertThat(preferences.all(user)).hasSize(NotificationCategory.values().length);
    }

    @Test
    void quietHoursAreSavedAndReadBack() {
        UUID user = newUser();
        var window = new NotificationPreferences.QuietWindow(true, LocalTime.of(22, 30), LocalTime.of(7, 0),
                ZoneId.of("Europe/London"));

        preferences.save(user, Map.of(), window);

        assertThat(preferences.quiet(user)).isEqualTo(window);
    }

    @Test
    void emailDueAtIsNullWhenEmailIsOff() {
        UUID user = newUser();

        assertThat(preferences.emailDueAt(user, NotificationCategory.RUN_FINISHED, Instant.now())).isNull();
    }

    @Test
    void emailDueAtIsNowOutsideQuietHoursAndHeldInsideThem() {
        UUID user = newUser();
        preferences.save(user, Map.of(NotificationCategory.RUN_STOPPED_EARLY,
                new NotificationPreferences.Channels(true, true)), new NotificationPreferences.QuietWindow(
                true, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("UTC")));
        Instant noon = Instant.parse("2026-09-30T12:00:00Z");
        Instant night = Instant.parse("2026-09-30T21:00:00Z");

        assertThat(preferences.emailDueAt(user, NotificationCategory.RUN_STOPPED_EARLY, noon)).isEqualTo(noon);
        assertThat(preferences.emailDueAt(user, NotificationCategory.RUN_STOPPED_EARLY, night))
                .isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void anApprovalRequestIsNeverHeldByQuietHours() {
        UUID user = newUser();
        preferences.save(user, Map.of(), new NotificationPreferences.QuietWindow(
                true, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("UTC")));
        Instant night = Instant.parse("2026-09-30T21:00:00Z");

        assertThat(preferences.emailDueAt(user, NotificationCategory.APPROVAL_NEEDED, night)).isEqualTo(night);
    }
}
