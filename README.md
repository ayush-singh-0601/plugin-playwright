<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png" alt="Kestra workflow orchestrator" />
  </a>
</p>

# Kestra Playwright plugin

Run declarative browser checks from Kestra flows. The `Check` task connects to a remote Playwright server, opens one browser context, performs an ordered list of interactions and assertions, and closes the session when it finishes.

The task supports Chromium, Firefox, and WebKit with these actions:

- `NAVIGATE`, `CLICK`, `FILL`, `PRESS`, and `WAIT_FOR`
- `ASSERT_VISIBLE`, `ASSERT_TEXT`, `ASSERT_URL`, and `ASSERT_TITLE`
- `SCREENSHOT`

Named screenshots are stored in Kestra internal storage. Failed actions produce a full-page screenshot and, when the flow has no `FILL` or `PRESS` action, a Playwright trace. A warning is logged when a requested trace is suppressed. Failure screenshots may show entered non-password values, so limit access to stored artifacts. Failure messages identify the action and artifact locations while omitting sensitive parts of URLs.

## Start a Playwright server

The server and Java client must use the same Playwright version. This plugin currently uses Playwright `1.63.0`:

```bash
docker run --rm -p 3000:3000 mcr.microsoft.com/playwright:v1.63.0-noble \
  npx -y playwright@1.63.0 run-server --port 3000 --host 0.0.0.0
```

The WebSocket endpoint is `ws://localhost:3000/`; use `wss://` when the server is behind TLS. Store it as a Kestra secret for shared environments.

The worker starts a local Playwright Node driver for each run, but browsers run on the remote server. The shaded JAR includes Linux x64 and ARM64 drivers only, so this build requires a Linux worker. Browser downloads are disabled on the worker.

## Example

```yaml
id: check_public_page
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
      - action: ASSERT_VISIBLE
        selector: h1
      - action: SCREENSHOT
        name: example.png
        fullPage: true
```

## Build and test

The integration tests start the matching Playwright server with Testcontainers, so Docker must be available.

```bash
./gradlew test
./gradlew build
```

To load the plugin in a local Kestra instance:

```bash
./gradlew shadowJar
docker compose up
```

The Kestra UI is then available at [localhost:8080](http://localhost:8080).

## Documentation

- [Plugin documentation](src/main/resources/doc/io.kestra.plugin.playwright.md)
- [Kestra plugin developer guide](https://kestra.io/docs/plugin-developer-guide/)

## License

Apache 2.0 © Kestra Technologies
