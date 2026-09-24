package com.agentstore.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local development: logs who would get what, never the body (bodies carry one-time links). */
@Component
@ConditionalOnProperty(name = "agentstore.email.mode", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("Email (not sent, email.mode=log) to={} subject={}", message.to(), message.subject());
    }
}
