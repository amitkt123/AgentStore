package com.agentstore.platform.common;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outgoing email in tests. Recipients are unique per test, so tests look up by recipient. */
@Component
@Primary
public class RecordingEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public Optional<EmailMessage> lastTo(String email) {
        String to = email.trim().toLowerCase(Locale.ROOT);
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).to().equals(to)) return Optional.of(sent.get(i));
        }
        return Optional.empty();
    }

    public static String tokenFromLink(EmailMessage message, String path) {
        Matcher m = Pattern.compile("/" + path + "/([A-Za-z0-9_-]+)").matcher(message.text());
        if (!m.find()) throw new AssertionError("No /" + path + "/ link in: " + message.text());
        return m.group(1);
    }
}
