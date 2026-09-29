package io.kestra.plugin.playwright;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.YamlParser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class CheckValidationTest {
    @Inject
    private ModelValidator modelValidator;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldAllowPopulatedActionsInFlowValidation() {
        var flow = YamlParser.parse("""
            id: playwright_validation
            namespace: company.team
            tasks:
              - id: check
                type: io.kestra.plugin.playwright.Check
                serverUrl: "{{ secret('PLAYWRIGHT_SERVER_URL') }}"
                actions:
                  - action: NAVIGATE
                    url: https://example.com
                  - action: ASSERT_TITLE
                    title: Example Domain
            """, Flow.class);

        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void shouldRejectEmptyActionsBeforeOpeningDriver() {
        var task = task(List.of());

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("actions must contain at least one"));
    }

    @Test
    void shouldValidateLaterActionsBeforeConnecting() {
        var task = task(List.of(
            Check.Action.builder().action(Check.ActionType.NAVIGATE).url("https://example.com").build(),
            Check.Action.builder().action(Check.ActionType.FILL).selector("#password").build()
        ));

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("actions[1].value for FILL is required"));
    }

    @Test
    void shouldRedactServerUrlOnConnectFailure() {
        var secret = "private-connection-token";
        var task = Check.builder()
            .id("connection-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue("ws://user:password@127.0.0.1:1/?token=" + secret))
            .actions(Property.ofValue(List.of(Check.Action.builder()
                .action(Check.ActionType.NAVIGATE).url("data:text/html,hello").build())))
            .timeout(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("Could not connect"));
        assertThat(exception.getMessage(), not(containsString(secret)));
        assertThat(exception.getMessage(), not(containsString("password")));
        assertNull(exception.getCause());
    }

    private Check task(List<Check.Action> actions) {
        return Check.builder()
            .id("validation-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue("ws://127.0.0.1:1/"))
            .actions(Property.ofValue(actions))
            .build();
    }
}
