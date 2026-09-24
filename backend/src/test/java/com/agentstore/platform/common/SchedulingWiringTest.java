package com.agentstore.platform.common;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunReaperJob;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.jobs.enabled=true")
class SchedulingWiringTest extends AbstractIntegrationTest {

    @Autowired
    LockProvider lockProvider;
    @Autowired
    RunReaperJob runReaperJob;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void reaperJobRunsUnderAShedLock() {
        runReaperJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'runReaper'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }
}
