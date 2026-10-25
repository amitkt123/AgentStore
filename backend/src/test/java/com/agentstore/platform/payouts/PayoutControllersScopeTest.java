package com.agentstore.platform.payouts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/** PayoutApiErrors only applies to PayoutApiController implementations, so every payouts controller must be one. */
class PayoutControllersScopeTest {

    @Test
    void everyPayoutsRestControllerImplementsTheMarker() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition d) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var found = scanner.findCandidateComponents("com.agentstore.platform.payouts");
        assertThat(found).isNotEmpty();
        for (var bd : found) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            assertThat(PayoutApiController.class).as(c.getSimpleName()).isAssignableFrom(c);
        }
    }
}
