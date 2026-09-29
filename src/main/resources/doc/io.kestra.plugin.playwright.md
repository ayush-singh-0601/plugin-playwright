# Playwright

Use the Playwright plugin for browser checks that should fail a Kestra task when a page, element, text value, URL, or title does not meet an expectation. Common uses include post-deploy smoke checks and scheduled synthetic checks of login or checkout paths.

## Authentication

The plugin connects to a remote Playwright server over WebSocket. It starts a local Playwright Node driver for each run, but does not launch or download browsers on the Kestra worker. The shaded JAR includes Linux x64 and ARM64 drivers only, so this build requires a Linux worker.

Playwright requires the server and Java client versions to match exactly. This plugin uses Playwright `1.63.0`. Start the matching server with:

```bash
docker run --rm -p 3000:3000 mcr.microsoft.com/playwright:v1.63.0-noble \
  npx -y playwright@1.63.0 run-server --port 3000 --host 0.0.0.0
```

Set `serverUrl` to the resulting endpoint, such as `ws://playwright:3000/`, or use `wss://` when the server is behind TLS. Store the endpoint in a Kestra secret if it contains credentials or other sensitive connection data.

When upgrading Playwright, update the Java dependency, Docker image tag, `npx` package version, examples, and documentation together.

## Tasks

`Check` runs every item in `actions` sequentially in one browser context:

| Action | Fields | Behavior |
| --- | --- | --- |
| `NAVIGATE` | `url` | Opens an HTTP, HTTPS, or data URL, or resolves a relative URL against `baseUrl`. Use a trusted Playwright server for pages with sensitive data. |
| `CLICK` | `selector` | Clicks the matching element. |
| `FILL` | `selector`, `value` | Replaces an input value. The rendered value is never logged. |
| `PRESS` | `selector`, `key` | Sends a key or shortcut such as `Enter` or `Control+A`. |
| `WAIT_FOR` | `selector` | Waits until the matching element is visible. |
| `SCREENSHOT` | `name`, optional `fullPage` | Stores a PNG in Kestra internal storage. `name` cannot contain `/`, `\`, or `..`. |
| `ASSERT_VISIBLE` | `selector` | Waits for the matching element to be visible. |
| `ASSERT_TEXT` | `selector`, `text`, optional `regex` | Waits for the element text to equal a string or match a Java regular expression. |
| `ASSERT_URL` | `url`, optional `regex` | Waits for the page URL to equal a string or match a Java regular expression. |
| `ASSERT_TITLE` | `title` | Waits for the page title to equal a string. |

Every action can have an `id`. When an action fails, the task message includes its zero-based index and ID, the selector when present, expected and actual values, and artifact URIs.

The `timeout` property applies to each action and assertion. It defaults to `PT30S` and accepts values up to `PT10M`.

## Screenshots and traces

Named `SCREENSHOT` actions are returned in the `screenshots` output as a map of names to internal storage URIs.

Tracing supports three modes:

- `ON_FAILURE` records a trace and stores it only when an action fails. This is the default.
- `ALWAYS` stores a trace for successful and failed checks. Successful trace URIs are returned in the `trace` output.
- `OFF` disables traces. Failure screenshots are still captured.

Tracing is automatically disabled for a run containing `FILL` or `PRESS`, even with `ON_FAILURE` or `ALWAYS`, because a trace can include the entered value. The task logs a warning when it suppresses a requested trace. Other traces can include page content and URLs. Use `OFF` for pages containing sensitive information that could appear in a trace. Failure screenshots are still captured and may show entered non-password values; limit access to stored artifacts.

Open a downloaded trace with the [Playwright Trace Viewer](https://trace.playwright.dev/).

## Post-deploy smoke check

```yaml
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

errors:
  - id: alert
    type: io.kestra.plugin.slack.SlackIncomingWebhook
    url: "{{ secret('SLACK_WEBHOOK_URL') }}"
    messageText: "Post-deploy smoke check failed on {{ inputs.app_url }}: {{ errorLogs()[0]['message'] }}"
```

## Scheduled synthetic check

Browser checks are actions, so scheduled checks use Kestra's core `Schedule` trigger:

```yaml
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
```

## Playwright and Selenium

Use this plugin when the flow expresses a browser check with assertions, automatic waiting, screenshots on failure, and diagnostic traces.

Use the Selenium plugin for broader browser automation such as scraping, form workflows, file downloads, and JavaScript execution against Selenium Grid.
