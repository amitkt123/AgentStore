package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A person's channel choices and quiet hours. */
@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferenceController {

    public record CategoryPref(NotificationCategory category, boolean web, boolean email) {
    }

    public record QuietHoursDto(boolean enabled, String start, String end, String timezone) {
    }

    public record PreferencesDto(List<CategoryPref> categories, QuietHoursDto quietHours) {
    }

    private final NotificationPreferences preferences;

    public NotificationPreferenceController(NotificationPreferences preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public PreferencesDto get() {
        var userId = claims().userId();
        List<CategoryPref> categories = preferences.all(userId).entrySet().stream()
                .map(e -> new CategoryPref(e.getKey(), e.getValue().web(), e.getValue().email())).toList();
        var quiet = preferences.quiet(userId);
        return new PreferencesDto(categories, new QuietHoursDto(quiet.enabled(), quiet.start().toString(),
                quiet.end().toString(), quiet.zone().getId()));
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@RequestBody PreferencesDto body) {
        Map<NotificationCategory, NotificationPreferences.Channels> changes = new EnumMap<>(NotificationCategory.class);
        if (body.categories() != null) {
            for (CategoryPref pref : body.categories()) {
                if (pref.category() == NotificationCategory.APPROVAL_NEEDED && !pref.web()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval requests always show on the web");
                }
                changes.put(pref.category(), new NotificationPreferences.Channels(pref.web(), pref.email()));
            }
        }
        NotificationPreferences.QuietWindow quiet = null;
        if (body.quietHours() != null) {
            try {
                LocalTime start = LocalTime.parse(body.quietHours().start());
                LocalTime end = LocalTime.parse(body.quietHours().end());
                if (start.equals(end)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiet hours must not be empty");
                }
                quiet = new NotificationPreferences.QuietWindow(body.quietHours().enabled(), start, end,
                        ZoneId.of(body.quietHours().timezone()));
            } catch (DateTimeException | NullPointerException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quiet hours");
            }
        }
        preferences.save(claims().userId(), changes, quiet);
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
