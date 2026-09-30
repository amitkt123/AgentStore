package com.agentstore.platform.events;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
public class OutboxTestConsumers {

    public static class RecordingConsumer implements DomainEventConsumer {
        private final List<UUID> handled = new CopyOnWriteArrayList<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();
        private volatile long delayMillis;

        @Override public String name() { return "recording"; }
        @Override public boolean handles(String type) { return "TEST_EVENT".equals(type); }

        @Override
        public void handle(DomainEvent event) {
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("transient");
            }
            handled.add(event.id());
        }

        public List<UUID> handled() { return handled; }
        public void failNext(int n) { failuresLeft.set(n); }
        public void delayMillis(long millis) { delayMillis = millis; }
        public void reset() { handled.clear(); failuresLeft.set(0); delayMillis = 0; }
    }

    public static class AlwaysFailingConsumer implements DomainEventConsumer {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public String name() { return "failing"; }
        @Override public boolean handles(String type) { return "TEST_FAIL".equals(type); }
        @Override public void handle(DomainEvent event) { calls.incrementAndGet(); throw new IllegalStateException("boom"); }
        public int calls() { return calls.get(); }
        public void reset() { calls.set(0); }
    }

    public static class OtherConsumer implements DomainEventConsumer {
        @Override public String name() { return "other"; }
        @Override public boolean handles(String type) { return "OTHER".equals(type); }
        @Override public void handle(DomainEvent event) { }
    }

    @Bean RecordingConsumer recordingConsumer() { return new RecordingConsumer(); }
    @Bean AlwaysFailingConsumer alwaysFailingConsumer() { return new AlwaysFailingConsumer(); }
    @Bean OtherConsumer otherConsumer() { return new OtherConsumer(); }
}
