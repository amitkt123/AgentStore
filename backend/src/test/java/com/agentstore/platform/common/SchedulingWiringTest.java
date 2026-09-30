package com.agentstore.platform.common;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.OutboxDispatcherJob;
import com.agentstore.platform.execution.RunReaperJob;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import com.agentstore.platform.notifications.EmailDeliveryJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enabling jobs spins up a live {@code @Scheduled} reaper against the shared Testcontainers Postgres. The
 * interval is set far beyond this test's runtime so it doesn't fire on its own, and {@code @DirtiesContext}
 * closes this context (and its scheduler) once the class is done, instead of leaving it cached and ticking in
 * the background for the rest of the build where it could reap runs other test classes are still using.
 */
@DirtiesContext
@TestPropertySource(properties = {"agentstore.jobs.enabled=true", "agentstore.runs.reaper-interval=PT1H",
        "agentstore.builds.poll-interval=PT1H", "agentstore.outbox.dispatch-interval=PT1H",
        "agentstore.notifications.email-interval=PT1H"})
class SchedulingWiringTest extends AbstractIntegrationTest {

    @Autowired
    LockProvider lockProvider;
    @Autowired
    RunReaperJob runReaperJob;
    @Autowired
    OutboxDispatcherJob outboxDispatcherJob;
    @Autowired
    EmailDeliveryJob emailDeliveryJob;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void reaperJobRunsUnderAShedLock() {
        runReaperJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'runReaper'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }

    @Test
    void outboxJobRunsUnderAShedLock() {
        outboxDispatcherJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'outboxDispatcher'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }

    @Test
    void emailJobRunsUnderAShedLock() {
        emailDeliveryJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'emailDelivery'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }
}
