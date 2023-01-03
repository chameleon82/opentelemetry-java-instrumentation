/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vaadin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vaadin.flow.server.Version;
import com.vaadin.flow.spring.annotation.EnableVaadin;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.http.AbstractHttpServerUsingTest;
import io.opentelemetry.instrumentation.testing.junit.http.HttpServerInstrumentationExtension;
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

public abstract class AbstractVaadinTest
    extends AbstractHttpServerUsingTest<ConfigurableApplicationContext> {

  private static final Logger logger = LoggerFactory.getLogger(AbstractVaadinTest.class);

  @RegisterExtension
  static final InstrumentationExtension testing = HttpServerInstrumentationExtension.forAgent();

  private BrowserWebDriverContainer<?> browser;

  @SpringBootApplication
  @EnableVaadin("test.vaadin")
  static class TestApplication {
    public TestApplication() {}

    static ConfigurableApplicationContext start(int port, String contextPath) {
      SpringApplication app = new SpringApplication(TestApplication.class);
      Map<String, Object> properties = new HashMap<>();
      properties.put("server.port", port);
      properties.put("server.servlet.contextPath", contextPath);
      properties.put("server.error.include-message", "always");
      app.setDefaultProperties(properties);
      return app.run();
    }
  }

  @BeforeAll
  void setup() throws URISyntaxException {
    startServer();

    Testcontainers.exposeHostPorts(port);

    browser =
        new BrowserWebDriverContainer<>()
            .withCapabilities(new ChromeOptions())
            .withLogConsumer(new Slf4jLogConsumer(logger));
    browser.start();

    address = new URI("http://host.testcontainers.internal:" + port + getContextPath() + "/");
  }

  @AfterAll
  void cleanup() {
    cleanupServer();
    if (browser != null) {
      browser.stop();
    }
  }

  @Override
  protected ConfigurableApplicationContext setupServer() {
    // set directory for files generated by vaadin development mode
    // by default these go to project root
    System.setProperty(
        "vaadin.project.basedir",
        new File("build/vaadin-" + Version.getFullVersion()).getAbsolutePath());
    return TestApplication.start(port, getContextPath());
  }

  @Override
  protected void stopServer(ConfigurableApplicationContext ctx) {
    ctx.close();
  }

  @Override
  public String getContextPath() {
    return "/xyz";
  }

  private void waitForStart(RemoteWebDriver driver) {
    // In development mode ui javascript is compiled when application starts
    // this involves downloading and installing npm and a bunch of packages
    // and running webpack. Wait until all of this is done before starting test.
    driver.manage().timeouts().implicitlyWait(3, TimeUnit.MINUTES);
    driver.get(address.resolve("main").toString());
    // wait for page to load
    driver.findElementById("main.label");
    // clear traces so test would start from clean state
    testing.clearData();

    driver.manage().timeouts().implicitlyWait(30, TimeUnit.SECONDS);
  }

  private RemoteWebDriver getWebDriver() {
    return browser.getWebDriver();
  }

  abstract void assertFirstRequest();

  private void assertButtonClick() {
    await()
        .untilAsserted(
            () -> {
              List<List<SpanData>> traces = testing.waitForTraces(1);
              assertThat(traces.get(0))
                  .satisfies(
                      spans -> {
                        OpenTelemetryAssertions.assertThat(spans.get(0))
                            .hasName(getContextPath() + "/main")
                            .hasNoParent()
                            .hasKind(SpanKind.SERVER);
                        OpenTelemetryAssertions.assertThat(spans.get(1))
                            .hasName("SpringVaadinServletService.handleRequest")
                            .hasParent(spans.get(0))
                            .hasKind(SpanKind.INTERNAL);
                        // we don't assert all the handler spans as these vary between
                        // vaadin versions
                        OpenTelemetryAssertions.assertThat(spans.get(spans.size() - 2))
                            .hasName("UidlRequestHandler.handleRequest")
                            .hasParent(spans.get(1))
                            .hasKind(SpanKind.INTERNAL);
                        OpenTelemetryAssertions.assertThat(spans.get(spans.size() - 1))
                            .hasName("EventRpcHandler.handle/click")
                            .hasParent(spans.get(spans.size() - 2))
                            .hasKind(SpanKind.INTERNAL);
                      });
            });
  }

  @Test
  public void navigateFromMainToOtherView() {
    RemoteWebDriver driver = getWebDriver();
    waitForStart(driver);

    // fetch the test page
    driver.get(address.resolve("main").toString());

    // wait for page to load
    assertThat(driver.findElementById("main.label").getText()).isEqualTo("Main view");
    assertFirstRequest();

    testing.clearData();

    // click a button to trigger calling java code in MainView
    driver.findElementById("main.button").click();

    // wait for page to load
    assertThat(driver.findElementById("other.label").getText()).isEqualTo("Other view");
    assertButtonClick();

    driver.close();
  }
}
