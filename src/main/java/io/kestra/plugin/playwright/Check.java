package io.kestra.plugin.playwright;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PageAssertions;
import io.kestra.core.exceptions.KilledException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run browser checks with Playwright",
    description = """
        Connects to a remote Playwright 1.63.0 server, opens one browser context, and runs an
        ordered list of browser actions and web-first assertions. A failed action stores a
        full-page screenshot and, when actions contain no `FILL` or `PRESS`, a Playwright trace
        in Kestra internal storage.
        The server and Java client must use the same Playwright version.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a post-deploy login smoke check",
            full = true,
            code = """
                id: post_deploy_smoke_check
                namespace: company.team

                inputs:
                  - id: app_url
                    type: STRING
                    defaults: https://staging.example.com

                tasks:
                  - id: smoke_check
                    type: io.kestra.plugin.playwright.Check
                    serverUrl: "{{ secret('PLAYWRIGHT_SERVER_URL') }}"
                    baseUrl: "{{ inputs.app_url }}"
                    actions:
                      - action: NAVIGATE
                        url: /login
                      - action: FILL
                        selector: "#email"
                        value: "{{ secret('SMOKE_TEST_USER') }}"
                      - action: FILL
                        selector: "#password"
                        value: "{{ secret('SMOKE_TEST_PASSWORD') }}"
                      - action: CLICK
                        selector: "button[type='submit']"
                      - action: ASSERT_URL
                        url: ".*/dashboard"
                        regex: true
                      - action: ASSERT_VISIBLE
                        id: dashboard_header
                        selector: "h1.dashboard-title"
                      - action: SCREENSHOT
                        name: dashboard.png
                """
        ),
        @Example(
            title = "Run a scheduled checkout check",
            full = true,
            code = """
                id: synthetic_checkout_check
                namespace: company.team

                triggers:
                  - id: every_15_minutes
                    type: io.kestra.plugin.core.trigger.Schedule
                    cron: "*/15 * * * *"

                tasks:
                  - id: checkout
                    type: io.kestra.plugin.playwright.Check
                    serverUrl: "{{ secret('PLAYWRIGHT_SERVER_URL') }}"
                    baseUrl: https://shop.example.com
                    trace: ALWAYS
                    actions:
                      - action: NAVIGATE
                        url: /products/demo-item
                      - action: CLICK
                        selector: "button.add-to-cart"
                      - action: NAVIGATE
                        url: /cart
                      - action: ASSERT_TEXT
                        selector: ".cart-count"
                        text: "1"
                      - action: ASSERT_VISIBLE
                        selector: "a.checkout"
                """
        )
    }
)
public class Check extends Task implements RunnableTask<Check.Output> {
    static final String PLAYWRIGHT_VERSION = "1.63.0";
    static final Duration DEFAULT_ACTION_TIMEOUT = Duration.ofSeconds(30);
    static final Duration MAX_ACTION_TIMEOUT = Duration.ofMinutes(10);

    @Schema(
        title = "Playwright server URL",
        description = """
            `ws://` or `wss://` endpoint of a remote Playwright server. Run the matching server with
            `mcr.microsoft.com/playwright:v1.63.0-noble` and
            `npx -y playwright@1.63.0 run-server --port 3000 --host 0.0.0.0`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> serverUrl;

    @Schema(title = "Browser", description = "Browser engine used for the check. Defaults to `CHROMIUM`.")
    @NotNull
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<Browser> browser = Property.ofValue(Browser.CHROMIUM);

    @Schema(title = "Actions", description = "Ordered browser actions and assertions to run in one browser context.")
    @NotNull
    @PluginProperty(group = "main")
    @ToString.Exclude
    // Check the rendered list in run(); @NotEmpty rejects populated Property lists during flow validation.
    private Property<List<@Valid Action>> actions;

    @Schema(
        title = "Base URL",
        description = "Optional base URL used to resolve relative URLs in `NAVIGATE` actions."
    )
    @PluginProperty(group = "main")
    private Property<String> baseUrl;

    @Schema(
        title = "Action timeout",
        description = "Maximum time for each action and assertion. Defaults to `PT30S`; allowed range is `PT0.001S` to `PT10M`."
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> actionTimeout = Property.ofValue(DEFAULT_ACTION_TIMEOUT);

    @Schema(
        title = "Failure screenshot",
        description = """
            Captures a full-page screenshot when an action fails. By default this is disabled for
            runs containing `FILL` or `PRESS`, because entered values may appear in the image.
            Set to `true` to opt in for those runs, or `false` to disable failure screenshots.
            """
    )
    @PluginProperty(group = "reliability")
    private Property<Boolean> failureScreenshot;

    @Schema(
        title = "Trace mode",
        description = """
            Controls trace recording: `OFF`, `ON_FAILURE` (default), or `ALWAYS`. Tracing is
            disabled with a warning for runs containing `FILL` or `PRESS` actions because traces
            can expose their values. Other traces may contain page content and URLs; use `OFF` for sensitive pages.
            """
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<TraceMode> trace = Property.ofValue(TraceMode.ON_FAILURE);

    @Builder.Default
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final AtomicReference<SessionResources> activeSession = new AtomicReference<>();

    @Builder.Default
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final AtomicBoolean killed = new AtomicBoolean(false);

    @Builder.Default
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rServerUrl = validateServerUrl(required(runContext.render(serverUrl).as(String.class).orElse(null), "serverUrl"));
        var rBrowser = runContext.render(browser).as(Browser.class).orElse(Browser.CHROMIUM);
        var rActions = runContext.render(actions).asList(Action.class);
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse(null);
        var rTimeout = runContext.render(actionTimeout).as(Duration.class).orElse(DEFAULT_ACTION_TIMEOUT);
        var rTrace = runContext.render(trace).as(TraceMode.class).orElse(TraceMode.ON_FAILURE);

        if (rActions.isEmpty()) {
            throw new IllegalArgumentException("actions must contain at least one browser action");
        }
        if (rTimeout.isZero() || rTimeout.isNegative() || rTimeout.compareTo(MAX_ACTION_TIMEOUT) > 0) {
            throw new IllegalArgumentException("actionTimeout must be between PT0.001S and PT10M");
        }
        checkKilled();
        checkStopped();
        validateActions(rActions, rBaseUrl);
        var sensitiveInput = rActions.stream().anyMatch(Check::entersSensitiveInput);
        var suppressTrace = rTrace != TraceMode.OFF && sensitiveInput;
        var rFailureScreenshot = runContext.render(failureScreenshot).as(Boolean.class).orElse(!sensitiveInput);
        if (suppressTrace) {
            runContext.logger().warn("Playwright tracing is disabled for this run because its actions contain FILL or PRESS; no trace will be stored");
        }
        if (sensitiveInput && !rFailureScreenshot) {
            runContext.logger().warn("Failure screenshots are disabled for this run because its actions contain FILL or PRESS");
        }

        var screenshots = new LinkedHashMap<String, URI>();
        URI traceUri = null;
        TraceRecorder traceRecorder = null;

        try {
            var resources = new SessionResources(createPlaywright(), runContext.logger());
            activeSession.set(resources);
            checkKilled();
            checkStopped();

            try {
                resources.browser = browserType(resources.playwright, rBrowser).connect(
                    rServerUrl,
                    new BrowserType.ConnectOptions().setTimeout(rTimeout.toMillis())
                );
            } catch (RuntimeException e) {
                checkKilled();
                checkStopped();
                throw new IllegalStateException(
                    "Could not connect to the Playwright " + rBrowser + " server. Confirm it is reachable and runs Playwright "
                        + PLAYWRIGHT_VERSION + "."
                );
            }
            checkKilled();
            checkStopped();

            var context = resources.browser.newContext();
            if (rTrace != TraceMode.OFF && !suppressTrace) {
                traceRecorder = new TraceRecorder(context);
                traceRecorder.start();
            }

            var page = context.newPage();
            page.setDefaultTimeout(rTimeout.toMillis());
            page.setDefaultNavigationTimeout(rTimeout.toMillis());

            for (var index = 0; index < rActions.size(); index++) {
                checkKilled();
                checkStopped();
                var action = required(rActions.get(index), "action at index " + index);
                logAction(runContext.logger(), index, action);

                try {
                    execute(action, page, runContext, screenshots, rBaseUrl, rTimeout);
                } catch (Exception | AssertionError e) {
                    checkKilled();
                    checkStopped();
                    var artifacts = captureFailureArtifacts(page, traceRecorder, runContext, index, rFailureScreenshot);
                    checkKilled();
                    checkStopped();
                    throw actionFailure(index, action, page, e, artifacts);
                }
            }

            if (rTrace == TraceMode.ALWAYS && traceRecorder != null) {
                traceUri = traceRecorder.stopAndStore(runContext, "trace.zip");
            } else if (traceRecorder != null) {
                traceRecorder.discard(runContext.logger());
            }

            checkKilled();
            checkStopped();
            return Output.builder()
                .screenshots(screenshots)
                .trace(traceUri)
                .build();
        } catch (Exception | AssertionError e) {
            checkKilled();
            checkStopped();
            throw e;
        } finally {
            if (traceRecorder != null && !killed.get() && !stopped.get()) {
                traceRecorder.discard(runContext.logger());
            }
            closeActiveSession();
        }
    }

    private void execute(
        Action action,
        Page page,
        RunContext runContext,
        Map<String, URI> screenshots,
        String rBaseUrl,
        Duration rTimeout
    ) throws Exception {
        var actionType = required(action.action, "action");
        var timeoutMillis = rTimeout.toMillis();

        switch (actionType) {
            case NAVIGATE -> page.navigate(resolveUrl(required(action.url, "url for NAVIGATE"), rBaseUrl));
            case CLICK -> page.locator(required(action.selector, "selector for CLICK")).click();
            case FILL -> page.locator(required(action.selector, "selector for FILL"))
                .fill(required(action.value, "value for FILL"));
            case PRESS -> page.locator(required(action.selector, "selector for PRESS"))
                .press(required(action.key, "key for PRESS"));
            case WAIT_FOR -> page.locator(required(action.selector, "selector for WAIT_FOR"))
                .waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis));
            case SCREENSHOT -> storeScreenshot(
                page,
                runContext,
                screenshots,
                screenshotName(required(action.name, "name for SCREENSHOT")),
                Boolean.TRUE.equals(action.fullPage)
            );
            case ASSERT_VISIBLE -> assertThat(page.locator(required(action.selector, "selector for ASSERT_VISIBLE")))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeoutMillis));
            case ASSERT_TEXT -> assertText(action, page, timeoutMillis);
            case ASSERT_URL -> assertUrl(action, page, timeoutMillis);
            case ASSERT_TITLE -> assertThat(page).hasTitle(
                required(action.title, "title for ASSERT_TITLE"),
                new PageAssertions.HasTitleOptions().setTimeout(timeoutMillis)
            );
        }
    }

    private Playwright createPlaywright() {
        try {
            return Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        } catch (RuntimeException e) {
            checkKilled();
            throw new IllegalStateException(
                "Could not start the Playwright driver. This plugin requires a Linux x64 or ARM64 worker."
            );
        }
    }

    static void validateActions(List<Action> actions, String baseUrl) {
        for (var index = 0; index < actions.size(); index++) {
            var action = required(actions.get(index), "actions[" + index + "]");
            var type = required(action.action, "actions[" + index + "].action");
            var prefix = "actions[" + index + "].";
            switch (type) {
                case NAVIGATE -> resolveUrl(required(action.url, prefix + "url"), baseUrl);
                case CLICK, WAIT_FOR, ASSERT_VISIBLE -> required(action.selector, prefix + "selector for " + type);
                case FILL -> {
                    required(action.selector, prefix + "selector for FILL");
                    required(action.value, prefix + "value for FILL");
                }
                case PRESS -> {
                    required(action.selector, prefix + "selector for PRESS");
                    required(action.key, prefix + "key for PRESS");
                }
                case SCREENSHOT -> screenshotName(required(action.name, prefix + "name for SCREENSHOT"));
                case ASSERT_TEXT -> {
                    required(action.selector, prefix + "selector for ASSERT_TEXT");
                    required(action.text, prefix + "text for ASSERT_TEXT");
                    if (Boolean.TRUE.equals(action.regex)) {
                        validatePattern(action.text, prefix + "text");
                    }
                }
                case ASSERT_URL -> {
                    required(action.url, prefix + "url for ASSERT_URL");
                    if (Boolean.TRUE.equals(action.regex)) {
                        validatePattern(action.url, prefix + "url");
                    }
                }
                case ASSERT_TITLE -> required(action.title, prefix + "title for ASSERT_TITLE");
            }
        }
    }

    private static void validatePattern(String value, String field) {
        try {
            Pattern.compile(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be a valid regular expression");
        }
    }

    private void assertText(Action action, Page page, double timeoutMillis) {
        var selector = required(action.selector, "selector for ASSERT_TEXT");
        var text = required(action.text, "text for ASSERT_TEXT");
        var options = new LocatorAssertions.HasTextOptions().setTimeout(timeoutMillis);
        var locator = page.locator(selector);

        if (Boolean.TRUE.equals(action.regex)) {
            assertThat(locator).hasText(Pattern.compile(text), options);
        } else {
            assertThat(locator).hasText(text, options);
        }
    }

    private void assertUrl(Action action, Page page, double timeoutMillis) {
        var url = required(action.url, "url for ASSERT_URL");
        var options = new PageAssertions.HasURLOptions().setTimeout(timeoutMillis);

        if (Boolean.TRUE.equals(action.regex)) {
            assertThat(page).hasURL(Pattern.compile(url), options);
        } else {
            assertThat(page).hasURL(url, options);
        }
    }

    private void storeScreenshot(
        Page page,
        RunContext runContext,
        Map<String, URI> screenshots,
        String name,
        boolean fullPage
    ) throws Exception {
        var path = runContext.workingDir().createTempFile(".png");
        page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage).setPath(path));
        var uri = runContext.storage().putFile(path.toFile(), name);

        if (screenshots.put(name, uri) != null) {
            runContext.logger().warn("Screenshot '{}' was replaced by a later action with the same name", name);
        }
    }

    private FailureArtifacts captureFailureArtifacts(
        Page page,
        TraceRecorder traceRecorder,
        RunContext runContext,
        int actionIndex,
        boolean failureScreenshot
    ) {
        URI screenshotUri = null;
        URI failureTraceUri = null;

        if (failureScreenshot) {
            try {
                var name = "failure-action-" + actionIndex + ".png";
                var path = runContext.workingDir().createTempFile(".png");
                page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(path));
                screenshotUri = runContext.storage().putFile(path.toFile(), name);
            } catch (Exception e) {
                runContext.logger().warn("Could not store the failure screenshot: {}", shortMessage(e));
            }
        }

        if (traceRecorder != null) {
            try {
                failureTraceUri = traceRecorder.stopAndStore(runContext, "failure-trace.zip");
            } catch (Exception e) {
                runContext.logger().warn("Could not store the failure trace: {}", shortMessage(e));
            }
        }

        return new FailureArtifacts(screenshotUri, failureTraceUri);
    }

    private IllegalStateException actionFailure(
        int index,
        Action action,
        Page page,
        Throwable cause,
        FailureArtifacts artifacts
    ) {
        var actionName = action.action == null ? "UNKNOWN" : action.action.name();
        var id = action.id == null || action.id.isBlank() ? "" : " (id '" + action.id + "')";
        var selector = action.selector == null ? "" : "; selector '" + action.selector + "'";
        var expected = expected(action);
        var actual = actual(action, page, cause);
        var screenshot = artifacts.screenshot() == null ? "unavailable" : artifacts.screenshot().toString();
        var failureTrace = artifacts.trace() == null ? "unavailable" : artifacts.trace().toString();

        var message = "Action " + index + id + " [" + actionName + "] failed" + selector
                + "; expected: " + expected
                + "; actual: " + actual
                + "; screenshot: " + screenshot
                + "; trace: " + failureTrace;
        return new IllegalStateException(message);
    }

    private String expected(Action action) {
        if (action.action == null) {
            return "a valid action type";
        }

        return switch (action.action) {
            case ASSERT_VISIBLE -> "element to be visible";
            case ASSERT_TEXT -> Boolean.TRUE.equals(action.regex)
                ? "text matching /" + action.text + "/"
                : "text '" + action.text + "'";
            case ASSERT_URL -> Boolean.TRUE.equals(action.regex)
                ? "URL matching /" + safeUrl(action.url) + "/"
                : "URL '" + safeUrl(action.url) + "'";
            case ASSERT_TITLE -> "title '" + action.title + "'";
            case NAVIGATE -> "navigation to '" + safeUrl(action.url) + "' to succeed";
            case SCREENSHOT -> "screenshot '" + action.name + "' to be stored";
            default -> "action to complete successfully";
        };
    }

    private String actual(Action action, Page page, Throwable cause) {
        if (action.action == null) {
            return cause.getClass().getSimpleName();
        }

        try {
            return switch (action.action) {
                case ASSERT_VISIBLE -> page.locator(action.selector).isVisible() ? "element is visible" : "element is not visible";
                case ASSERT_TEXT -> {
                    var locator = page.locator(action.selector);
                    yield locator.count() == 0
                        ? "no element matched the selector"
                        : "text '" + truncate(locator.first().textContent(new Locator.TextContentOptions().setTimeout(1_000))) + "'";
                }
                case ASSERT_URL -> "URL '" + safeUrl(page.url()) + "'";
                case ASSERT_TITLE -> "title '" + page.title() + "'";
                case FILL -> "the input could not be filled";
                case PRESS -> "the key could not be pressed";
                case NAVIGATE -> "navigation failed";
                default -> cause.getClass().getSimpleName();
            };
        } catch (Exception e) {
            return cause.getClass().getSimpleName();
        }
    }

    static String resolveUrl(String url, String rBaseUrl) {
        final URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url for NAVIGATE is invalid: " + safeUrl(url));
        }

        URI resolved = target;
        if (!target.isAbsolute()) {
            if (rBaseUrl == null || rBaseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required when NAVIGATE uses a relative url");
            }
            final URI base;
            try {
                base = URI.create(rBaseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("baseUrl must be a valid HTTP or HTTPS URL");
            }
            if (!base.isAbsolute() || !isHttpScheme(base.getScheme())) {
                throw new IllegalArgumentException("baseUrl must be an absolute HTTP or HTTPS URL");
            }
            resolved = base.resolve(target);
        }
        var scheme = resolved.getScheme();
        if (!isHttpScheme(scheme) && !"data".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("url for NAVIGATE must use http, https, or data");
        }
        return resolved.toString();
    }

    static String validateServerUrl(String serverUrl) {
        try {
            var uri = URI.create(serverUrl);
            var scheme = uri.getScheme();
            if (("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                return serverUrl;
            }
        } catch (IllegalArgumentException ignored) {
            // Do not include the URL in the error: it may contain credentials or tokens.
        }
        throw new IllegalArgumentException("serverUrl must be an absolute ws:// or wss:// URL with a host");
    }

    static String safeUrl(String url) {
        if (url == null) {
            return "<unavailable>";
        }
        try {
            var uri = URI.create(url);
            if (uri.isOpaque()) {
                return uri.getScheme() + ":<redacted>";
            }
            if (uri.getHost() != null) {
                return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
            }
            if (uri.getScheme() == null && uri.getRawAuthority() == null) {
                return new URI(null, null, uri.getPath(), null, null).toString();
            }
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            // Invalid or pattern-based URLs are intentionally omitted from failure messages.
        }
        return "<redacted URL>";
    }

    private static boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private BrowserType browserType(Playwright playwright, Browser rBrowser) {
        return switch (rBrowser) {
            case CHROMIUM -> playwright.chromium();
            case FIREFOX -> playwright.firefox();
            case WEBKIT -> playwright.webkit();
        };
    }

    private void logAction(Logger logger, int index, Action action) {
        var id = action.id == null || action.id.isBlank() ? "" : " (" + action.id + ")";
        if (action.selector == null) {
            logger.info("Executing action [{}]{}: {}", index, id, action.action);
        } else {
            logger.info("Executing action [{}]{}: {} on selector '{}'", index, id, action.action, action.selector);
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null || value instanceof String string && string.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String shortMessage(Throwable throwable) {
        var message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message).strip();
    }

    static String screenshotName(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("screenshot name must not contain '/', '\\', or '..'");
        }
        return name;
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static boolean entersSensitiveInput(Action action) {
        return action != null && (action.action == ActionType.FILL || action.action == ActionType.PRESS);
    }

    private void checkKilled() {
        if (killed.get()) {
            throw new KilledException();
        }
    }

    private void checkStopped() {
        if (stopped.get()) {
            throw new IllegalStateException("Playwright check stopped during worker shutdown");
        }
    }

    @Override
    public void kill() {
        if (killed.compareAndSet(false, true)) {
            closeActiveSessionAsync();
        }
    }

    @Override
    public void stop() {
        stopped.set(true);
        closeActiveSessionAsync();
    }

    private void closeActiveSessionAsync() {
        var resources = activeSession.getAndSet(null);
        if (resources != null) {
            Thread.startVirtualThread(() -> closeSession(resources));
        }
    }

    private void closeActiveSession() {
        var resources = activeSession.getAndSet(null);
        if (resources != null) {
            closeSession(resources);
        }
    }

    private void closeSession(SessionResources resources) {
        if (resources.browser != null) {
            try {
                resources.browser.close();
            } catch (Exception e) {
                resources.logger.warn("Could not close the remote Playwright browser: {}", shortMessage(e));
            }
        }

        try {
            resources.playwright.close();
        } catch (Exception e) {
            resources.logger.warn("Could not close the Playwright connection: {}", shortMessage(e));
        }
    }

    public enum Browser {
        CHROMIUM,
        FIREFOX,
        WEBKIT
    }

    public enum TraceMode {
        OFF,
        ON_FAILURE,
        ALWAYS
    }

    public enum ActionType {
        NAVIGATE,
        CLICK,
        FILL,
        PRESS,
        WAIT_FOR,
        SCREENSHOT,
        ASSERT_VISIBLE,
        ASSERT_TEXT,
        ASSERT_URL,
        ASSERT_TITLE
    }

    @Builder
    @Getter
    @ToString
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        @Schema(
            title = "Action",
            description = """
                Action to execute: `NAVIGATE` (`url`), `CLICK` (`selector`), `FILL` (`selector`, `value`),
                `PRESS` (`selector`, `key`), `WAIT_FOR` (`selector`), `SCREENSHOT` (`name`, optional `fullPage`),
                `ASSERT_VISIBLE` (`selector`), `ASSERT_TEXT` (`selector`, `text`, optional `regex`),
                `ASSERT_URL` (`url`, optional `regex`), or `ASSERT_TITLE` (`title`).
                """
        )
        @NotNull
        @PluginProperty(group = "main")
        private ActionType action;

        @Schema(title = "Action ID", description = "Optional identifier included in failure messages.")
        @PluginProperty(group = "main")
        private String id;

        @Schema(title = "URL", description = "HTTP, HTTPS, or data target for `NAVIGATE`, or expected URL for `ASSERT_URL`.")
        @PluginProperty(group = "main")
        private String url;

        @Schema(title = "Selector", description = "Playwright selector used by element actions and assertions.")
        @PluginProperty(group = "main")
        private String selector;

        @Schema(title = "Value", description = "Text entered by `FILL`. The rendered value is never logged.")
        @PluginProperty(group = "main", secret = true)
        @ToString.Exclude
        private String value;

        @Schema(title = "Key", description = "Keyboard key or shortcut sent by `PRESS`, such as `Enter` or `Control+A`.")
        @PluginProperty(group = "main", secret = true)
        @ToString.Exclude
        private String key;

        @Schema(title = "Screenshot name", description = "Internal storage filename used by `SCREENSHOT`; cannot contain `/`, `\\`, or `..`.")
        @PluginProperty(group = "destination")
        private String name;

        @Schema(title = "Full-page screenshot", description = "Capture the full scrollable page. Defaults to `false`.")
        @PluginProperty(group = "main")
        private Boolean fullPage;

        @Schema(title = "Expected text", description = "Expected element text used by `ASSERT_TEXT`.")
        @PluginProperty(group = "main")
        private String text;

        @Schema(title = "Regular expression", description = "Treat `text` or `url` as a Java regular expression. Defaults to `false`.")
        @PluginProperty(group = "main")
        private Boolean regex;

        @Schema(title = "Expected title", description = "Expected page title used by `ASSERT_TITLE`.")
        @PluginProperty(group = "main")
        private String title;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Screenshots", description = "Internal storage URIs keyed by `SCREENSHOT` action name.")
        @Builder.Default
        private Map<String, URI> screenshots = new LinkedHashMap<>();

        @Schema(title = "Trace", description = "Playwright trace URI when `trace` is `ALWAYS` and no `FILL` or `PRESS` action is present. A warning is logged when tracing is suppressed; failure trace URIs are included in error messages.")
        private URI trace;
    }

    private record FailureArtifacts(URI screenshot, URI trace) {
    }

    private static final class SessionResources {
        private final Playwright playwright;
        private final Logger logger;
        private volatile com.microsoft.playwright.Browser browser;

        private SessionResources(Playwright playwright, Logger logger) {
            this.playwright = playwright;
            this.logger = logger;
        }
    }

    private static final class TraceRecorder {
        private final BrowserContext context;
        private boolean recording;

        private TraceRecorder(BrowserContext context) {
            this.context = context;
        }

        private void start() {
            context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
            recording = true;
        }

        private URI stopAndStore(RunContext runContext, String name) throws Exception {
            var path = runContext.workingDir().createTempFile(".zip");
            try {
                context.tracing().stop(new Tracing.StopOptions().setPath(path));
            } finally {
                recording = false;
            }
            return runContext.storage().putFile(path.toFile(), name);
        }

        private void discard(Logger logger) {
            if (!recording) {
                return;
            }

            try {
                context.tracing().stop();
            } catch (Exception e) {
                logger.warn("Could not stop Playwright tracing: {}", shortMessage(e));
            } finally {
                recording = false;
            }
        }
    }
}
