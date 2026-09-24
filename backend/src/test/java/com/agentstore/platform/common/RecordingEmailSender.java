package com.agentstore.platform.common;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outgoing email in tests. Recipients are unique per test, so tests look up by recipient. */
@Component
@Primary
public class RecordingEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    @Override
    public void send(EmailMessage message) {
        if (failing.remove(message.to())) {
            throw new RuntimeException("Simulated email delivery failure for test");
        }
        sent.add(message);
    }

    /** The next send to this address throws instead of recording, then behaves normally again. */
    public void failNextSendTo(String email) {
        failing.add(email.trim().toLowerCase(Locale.ROOT));
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
