package io.kestra.plugin.playwright;

import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckUnitTest {
    @Test
    void shouldResolveRelativeNavigationUrls() {
        assertThat(Check.resolveUrl("/login", "https://example.com/app/"), is("https://example.com/login"));
        assertThat(Check.resolveUrl("next", "https://example.com/app/"), is("https://example.com/app/next"));
        assertThat(Check.resolveUrl("data:text/html,hello", null), is("data:text/html,hello"));
    }

    @Test
    void shouldRejectRelativeUrlWithoutBaseUrl() {
        var exception = assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("/login", null));

        assertThat(exception.getMessage(), containsString("baseUrl is required"));
    }

    @Test
    void shouldRejectUnsafeNavigationSchemesAndNameInvalidUrls() {
        assertThat(assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("file:///etc/passwd", null)).getMessage(),
            containsString("http, https, or data"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> Check.resolveUrl("http://bad host", null)).getMessage(),
            containsString("url for NAVIGATE is invalid"));
    }

    @Test
    void shouldRedactSensitivePartsOfUrls() {
        assertThat(Check.safeUrl("https://user:password@example.com/path?code=secret#fragment"), is("https://example.com/path"));
        assertThat(Check.safeUrl("/login?token=secret#fragment"), is("/login"));
        assertThat(Check.safeUrl("data:text/html,secret"), is("data:<redacted>"));
    }

    @Test
    void shouldExcludeFillValuesFromGeneratedStrings() {
        var action = Check.Action.builder()
            .action(Check.ActionType.FILL)
            .selector("#password")
            .value("highly-sensitive-value")
            .build();
        var task = Check.builder()
            .id("secret-check")
            .type(Check.class.getName())
            .actions(Property.ofValue(List.of(action)))
            .build();

        assertThat(action.toString(), not(containsString("highly-sensitive-value")));
        assertThat(task.toString(), not(containsString("highly-sensitive-value")));
    }

    @Test
    void shouldRejectUnsafeScreenshotNames() {
        for (var name : List.of("../capture.png", "folder/capture.png", "folder\\capture.png")) {
            assertThrows(IllegalArgumentException.class, () -> Check.screenshotName(name));
        }
        assertThat(Check.screenshotName("capture.png"), is("capture.png"));
    }
}
