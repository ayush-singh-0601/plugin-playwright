package io.kestra.plugin.playwright;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@Testcontainers
class CheckTest {
    private static final String IMAGE = "mcr.microsoft.com/playwright:v1.63.0-noble";

    @Container
    static final GenericContainer<?> PLAYWRIGHT = new GenericContainer<>(DockerImageName.parse(IMAGE))
        .withExposedPorts(3000)
        .withCommand(
            "npx", "-y", "playwright@1.63.0", "run-server",
            "--port", "3000", "--host", "0.0.0.0"
        )
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofMinutes(5));

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRunActionsAndStoreScreenshotWithoutSensitiveTrace() throws Exception {
        var task = task(
            Property.ofValue(List.of(
                action(Check.ActionType.NAVIGATE).url(page("""
                    <html>
                      <head><title>Smoke page</title></head>
                      <body>
                        <input id="message" />
                        <button id="show" onclick="document.querySelector('#result').style.display='block'; document.querySelector('#result').textContent=document.querySelector('#message').value + ' ready';">Show</button>
                        <div id="result" style="display:none"></div>
                      </body>
                    </html>
                    """)).build(),
                action(Check.ActionType.ASSERT_TITLE).title("Smoke page").build(),
                action(Check.ActionType.FILL).selector("#message").value("Kestra").build(),
                action(Check.ActionType.PRESS).selector("#message").key("Tab").build(),
                action(Check.ActionType.CLICK).selector("#show").build(),
                action(Check.ActionType.WAIT_FOR).selector("#result").build(),
                action(Check.ActionType.ASSERT_VISIBLE).selector("#result").build(),
                action(Check.ActionType.ASSERT_TEXT).selector("#result").text("Kestra\\s+ready").regex(true).build(),
                action(Check.ActionType.ASSERT_URL).url("^data:text/html;base64,.*").regex(true).build(),
                action(Check.ActionType.SCREENSHOT).name("smoke.png").fullPage(true).build()
            )),
            Check.TraceMode.ALWAYS
        );
        var runContext = runContextFactory.of();

        var output = task.run(runContext);

        assertThat(output.getScreenshots(), hasEntry(is("smoke.png"), notNullValue()));
        assertStored(runContext, output.getScreenshots().get("smoke.png"));
        assertThat(output.getTrace(), nullValue());
    }

    @Test
    void shouldStoreTraceWhenActionsContainNoSensitiveInput() throws Exception {
        var task = task(
            Property.ofValue(List.of(
                action(Check.ActionType.NAVIGATE).url(page("<html><head><title>Trace page</title></head></html>")).build(),
                action(Check.ActionType.ASSERT_TITLE).title("Trace page").build()
            )),
            Check.TraceMode.ALWAYS
        );
        var runContext = runContextFactory.of();

        var output = task.run(runContext);

        assertThat(output.getTrace(), notNullValue());
        assertStored(runContext, output.getTrace());
    }

    @Test
    void shouldConnectToEveryBrowserEngine() throws Exception {
        for (var engine : Check.Browser.values()) {
            var task = Check.builder()
                .id("browser-" + UUID.randomUUID())
                .type(Check.class.getName())
                .serverUrl(Property.ofValue(serverUrl()))
                .browser(Property.ofValue(engine))
                .actions(Property.ofValue(List.of(
                    action(Check.ActionType.NAVIGATE).url(page("<html><head><title>Browser check</title></head></html>")).build(),
                    action(Check.ActionType.ASSERT_TITLE).title("Browser check").build()
                )))
                .trace(Property.ofValue(Check.TraceMode.OFF))
                .build();

            task.run(runContextFactory.of());
        }
    }

    @Test
    void shouldNavigateRelativeUrlAgainstBaseUrl() throws Exception {
        var task = Check.builder()
            .id("relative-url-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue(serverUrl()))
            .baseUrl(Property.ofValue("http://localhost:3000/root/"))
            .actions(Property.ofValue(List.of(
                action(Check.ActionType.NAVIGATE).url("/check").build(),
                action(Check.ActionType.ASSERT_URL).url("http://localhost:3000/check").build()
            )))
            .trace(Property.ofValue(Check.TraceMode.OFF))
            .build();

        task.run(runContextFactory.of());
    }

    @Test
    void shouldRedactExpectedAndActualUrlsOnAssertionFailure() throws Exception {
        var task = Check.builder()
            .id("url-failure-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue(serverUrl()))
            .actions(Property.ofValue(List.of(
                action(Check.ActionType.NAVIGATE).url(page("<html><body>private page content</body></html>")).build(),
                action(Check.ActionType.ASSERT_URL)
                    .url("https://user:password@example.com/dashboard?code=private-code#private-fragment")
                    .build()
            )))
            .trace(Property.ofValue(Check.TraceMode.OFF))
            .timeout(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("expected: URL 'https://example.com/dashboard'"));
        assertThat(exception.getMessage(), containsString("actual: URL 'data:<redacted>'"));
        for (var secret : List.of("user", "password", "private-code", "private-fragment", "private page content")) {
            assertThat(exception.getMessage(), not(containsString(secret)));
        }
        assertThat(exception.getCause(), nullValue());
    }

    @Test
    void shouldStoreFailureArtifactsAndDescribeTheFailedAssertion() throws Exception {
        var task = task(
            Property.ofValue(List.of(
                action(Check.ActionType.NAVIGATE)
                    .url(page("<html><body><div id='status'>Actual value</div></body></html>"))
                    .build(),
                action(Check.ActionType.ASSERT_TEXT)
                    .id("status_copy")
                    .selector("#status")
                    .text("Expected value")
                    .build()
            )),
            Check.TraceMode.ON_FAILURE
        );
        var runContext = runContextFactory.of();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(exception.getMessage(), containsString("Action 1 (id 'status_copy') [ASSERT_TEXT] failed"));
        assertThat(exception.getMessage(), containsString("selector '#status'"));
        assertThat(exception.getMessage(), containsString("expected: text 'Expected value'"));
        assertThat(exception.getMessage(), containsString("actual: text 'Actual value'"));

        var artifactPattern = Pattern.compile("screenshot: ([^;]+); trace: (\\S+)$");
        var matcher = artifactPattern.matcher(exception.getMessage());
        assertThat(matcher.find(), is(true));
        assertStored(runContext, URI.create(matcher.group(1)));
        assertStored(runContext, URI.create(matcher.group(2)));
    }

    @Test
    void shouldRenderPebbleExpressionsInsideActions() throws Exception {
        var actions = List.of(
            action(Check.ActionType.NAVIGATE)
                .url(page("<html><body><input id='message'/><div id='result'></div><script>document.querySelector('#message').addEventListener('input', event => document.querySelector('#result').textContent = event.target.value);</script></body></html>"))
                .build(),
            action(Check.ActionType.FILL).selector("#message").value("{{ inputs.message }}").build(),
            action(Check.ActionType.ASSERT_TEXT).selector("#result").text("{{ inputs.message }}").build(),
            action(Check.ActionType.SCREENSHOT).name("{{ inputs.screenshot }}").build()
        );
        var actionsExpression = Property.<List<Check.Action>>ofExpression(
            JacksonMapper.ofJson().writeValueAsString(actions)
        );
        var task = task(actionsExpression, Check.TraceMode.OFF);
        var runContext = runContextFactory.of(Map.of(
            "inputs", Map.of("message", "Rendered value", "screenshot", "rendered.png")
        ));

        var output = task.run(runContext);

        assertThat(output.getScreenshots().containsKey("rendered.png"), is(true));
        assertStored(runContext, output.getScreenshots().get("rendered.png"));
    }

    @Test
    void shouldKeepFailedFillValueOutOfExceptionChainAndTrace() throws Exception {
        var secret = "password-never-in-an-error";
        var task = Check.builder()
            .id("secret-failure-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue(serverUrl()))
            .actions(Property.ofValue(List.of(
                action(Check.ActionType.NAVIGATE).url(page("<html><body><input id='other'/></body></html>")).build(),
                action(Check.ActionType.FILL).selector("#missing").value(secret).build()
            )))
            .trace(Property.ofValue(Check.TraceMode.ALWAYS))
            .timeout(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(exception.getMessage(), containsString("trace: unavailable"));
        for (Throwable current = exception; current != null; current = current.getCause()) {
            assertThat(current.toString(), not(containsString(secret)));
        }
    }

    @Test
    void shouldReportKillDuringActionAsKilled() throws Exception {
        assertCancellation(Check::kill);
    }

    @Test
    void shouldReportStopDuringActionAsKilled() throws Exception {
        assertCancellation(Check::stop);
    }

    private void assertCancellation(Consumer<Check> cancel) throws Exception {
        var task = Check.builder()
            .id("cancel-check-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue(serverUrl()))
            .actions(Property.ofValue(List.of(
                action(Check.ActionType.WAIT_FOR).selector("#never-present").build()
            )))
            .trace(Property.ofValue(Check.TraceMode.OFF))
            .timeout(Property.ofValue(Duration.ofSeconds(30)))
            .build();
        var failure = new AtomicReference<Throwable>();
        var runContext = runContextFactory.of();
        var thread = Thread.startVirtualThread(() -> {
            try {
                task.run(runContext);
            } catch (Throwable e) {
                failure.set(e);
            }
        });

        try {
            awaitBrowserConnection(task, thread);
            assertThat(thread.isAlive(), is(true));
            cancel.accept(task);
            thread.join(15_000);
            assertThat(thread.isAlive(), is(false));
            assertThat(failure.get(), instanceOf(KilledException.class));
        } finally {
            task.kill();
        }
    }

    private void awaitBrowserConnection(Check task, Thread thread) throws Exception {
        Field sessionField = Check.class.getDeclaredField("activeSession");
        sessionField.setAccessible(true);
        var activeSession = (AtomicReference<?>) sessionField.get(task);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

        while (thread.isAlive() && System.nanoTime() < deadline) {
            var session = activeSession.get();
            if (session != null) {
                Field browserField = session.getClass().getDeclaredField("browser");
                browserField.setAccessible(true);
                if (browserField.get(session) != null) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Playwright did not connect before the cancellation deadline");
    }

    private Check task(Property<List<Check.Action>> actions, Check.TraceMode traceMode) {
        return Check.builder()
            .id("check-" + UUID.randomUUID())
            .type(Check.class.getName())
            .serverUrl(Property.ofValue(serverUrl()))
            .actions(actions)
            .trace(Property.ofValue(traceMode))
            .build();
    }

    private Check.Action.ActionBuilder action(Check.ActionType type) {
        return Check.Action.builder().action(type);
    }

    private String serverUrl() {
        return "ws://" + PLAYWRIGHT.getHost() + ":" + PLAYWRIGHT.getMappedPort(3000) + "/";
    }

    private String page(String html) {
        return "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
    }

    private void assertStored(RunContext runContext, URI uri) throws Exception {
        assertThat(runContext.storage().isFileExist(uri), is(true));
        try (var input = runContext.storage().getFile(uri)) {
            assertThat(input.readAllBytes().length, greaterThan(0));
        }
    }
}
