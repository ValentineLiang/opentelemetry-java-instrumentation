/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.spring.webflux.v5_0.server;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.EXCEPTION_EVENT_NAME;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.EXCEPTION_MESSAGE;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.EXCEPTION_STACKTRACE;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.EXCEPTION_TYPE;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_FLAVOR;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_METHOD;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_REQUEST_CONTENT_LENGTH;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_RESPONSE_CONTENT_LENGTH;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_ROUTE;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_SCHEME;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_STATUS_CODE;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_TARGET;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_USER_AGENT;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NET_HOST_NAME;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NET_HOST_PORT;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NET_SOCK_HOST_ADDR;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NET_SOCK_PEER_ADDR;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NET_SOCK_PEER_PORT;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NET_TRANSPORT;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NetTransportValues.IP_TCP;
import static org.junit.jupiter.api.Named.named;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.sdk.testing.assertj.TraceAssert;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.testing.internal.armeria.client.WebClient;
import io.opentelemetry.testing.internal.armeria.common.AggregatedHttpResponse;
import io.opentelemetry.testing.internal.armeria.common.HttpStatus;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import server.EchoHandlerFunction;
import server.FooModel;
import server.SpringWebFluxTestApplication;
import server.TestController;

@ExtendWith(SpringExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {
      SpringWebFluxTestApplication.class,
      SpringWebfluxTest.ForceNettyAutoConfiguration.class
    })
public class SpringWebfluxTest {
  @TestConfiguration
  static class ForceNettyAutoConfiguration {
    @Bean
    NettyReactiveWebServerFactory nettyFactory() {
      return new NettyReactiveWebServerFactory();
    }
  }

  @RegisterExtension
  static final InstrumentationExtension testing = AgentInstrumentationExtension.create();

  private static final String INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX =
      SpringWebFluxTestApplication.class.getName() + "$";
  private static final String SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX =
      SpringWebFluxTestApplication.class.getSimpleName() + "$";

  // can't use @LocalServerPort annotation since it moved packages between Spring Boot 2 and 3
  @Value("${local.server.port}")
  private int port;

  private WebClient client;

  @BeforeEach
  void beforeEach() {
    client = WebClient.builder("h1c://localhost:" + port).followRedirects().build();
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("provideParameters")
  void basicGetTest(Parameter parameter) {
    AggregatedHttpResponse response = client.get(parameter.urlPath).aggregate().join();

    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.contentUtf8()).isEqualTo(parameter.expectedResponseBody);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET " + parameter.urlPathWithVariables)
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, parameter.urlPath),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 200),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, parameter.urlPathWithVariables),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span -> {
                  if (parameter.annotatedMethod == null) {
                    // Functional API
                    assertThat(trace.getSpan(1).getName())
                        .contains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle");
                  } else {
                    // Annotation API
                    span.hasName(
                        TestController.class.getSimpleName() + "." + parameter.annotatedMethod);
                  }
                  span.hasKind(SpanKind.INTERNAL)
                      .hasParent(trace.getSpan(0))
                      .hasAttributesSatisfyingExactly(
                          satisfies(
                              stringKey("spring-webflux.handler.type"),
                              parameter.annotatedMethod == null
                                  ? val -> val.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
                                  : val -> val.isEqualTo(TestController.class.getName())));
                }));
  }

  private static Stream<Arguments> provideParameters() {
    return Stream.of(
        Arguments.of(
            named(
                "functional API without parameters",
                new Parameter(
                    "/greet",
                    "/greet",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE))),
        Arguments.of(
            named(
                "functional API with one parameter",
                new Parameter(
                    "/greet/WORLD",
                    "/greet/{name}",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " WORLD"))),
        Arguments.of(
            named(
                "functional API with two parameters",
                new Parameter(
                    "/greet/World/Test1",
                    "/greet/{name}/{word}",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE
                        + " World Test1"))),
        Arguments.of(
            named(
                "functional API delayed response",
                new Parameter(
                    "/greet-delayed",
                    "/greet-delayed",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE))),
        Arguments.of(
            named(
                "annotation API without parameters",
                new Parameter(
                    "/foo", "/foo", "getFooModel", new FooModel(0L, "DEFAULT").toString()))),
        Arguments.of(
            named(
                "annotation API with one parameter",
                new Parameter(
                    "/foo/1", "/foo/{id}", "getFooModel", new FooModel(1L, "pass").toString()))),
        Arguments.of(
            named(
                "annotation API with two parameters",
                new Parameter(
                    "/foo/2/world",
                    "/foo/{id}/{name}",
                    "getFooModel",
                    new FooModel(2L, "world").toString()))),
        Arguments.of(
            named(
                "annotation API delayed response",
                new Parameter(
                    "/foo-delayed",
                    "/foo-delayed",
                    "getFooDelayed",
                    new FooModel(3L, "delayed").toString()))));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("provideAsyncParameters")
  void getAsyncResponseTest(Parameter parameter) {
    AggregatedHttpResponse response = client.get(parameter.urlPath).aggregate().join();

    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.contentUtf8()).isEqualTo(parameter.expectedResponseBody);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET " + parameter.urlPathWithVariables)
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, parameter.urlPath),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 200),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, parameter.urlPathWithVariables),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span -> {
                  if (parameter.annotatedMethod == null) {
                    // Functional API
                    assertThat(trace.getSpan(1).getName())
                        .contains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle");
                  } else {
                    // Annotation API
                    span.hasName(
                        TestController.class.getSimpleName() + "." + parameter.annotatedMethod);
                  }
                  span.hasKind(SpanKind.INTERNAL)
                      .hasParent(trace.getSpan(0))
                      .hasAttributesSatisfyingExactly(
                          satisfies(
                              stringKey("spring-webflux.handler.type"),
                              parameter.annotatedMethod == null
                                  ? val -> val.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
                                  : val -> val.isEqualTo(TestController.class.getName())));
                },
                span ->
                    span.hasName("tracedMethod")
                        .hasParent(trace.getSpan(0))
                        .hasTotalAttributeCount(0)));
  }

  private static Stream<Arguments> provideAsyncParameters() {
    return Stream.of(
        Arguments.of(
            named(
                "functional API traced method from mono",
                new Parameter(
                    "/greet-mono-from-callable/4",
                    "/greet-mono-from-callable/{id}",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " 4"))),
        Arguments.of(
            named(
                "functional API traced method with delay",
                new Parameter(
                    "/greet-delayed-mono/6",
                    "/greet-delayed-mono/{id}",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " 6"))),
        Arguments.of(
            named(
                "annotation API traced method from mono",
                new Parameter(
                    "/foo-mono-from-callable/7",
                    "/foo-mono-from-callable/{id}",
                    "getMonoFromCallable",
                    new FooModel(7L, "tracedMethod").toString()))),
        Arguments.of(
            named(
                "annotation API traced method with delay",
                new Parameter(
                    "/foo-delayed-mono/9",
                    "/foo-delayed-mono/{id}",
                    "getFooDelayedMono",
                    new FooModel(9L, "tracedMethod").toString()))));
  }

  /*
  This test differs from the previous in one important aspect.
  The test above calls endpoints which does not create any spans during their invocation.
  They merely assemble reactive pipeline where some steps create spans.
  Thus all those spans are created when WebFlux span created by DispatcherHandlerInstrumentation
  has already finished. Therefore, they have `SERVER` span as their parent.

  This test below calls endpoints which do create spans right inside endpoint handler.
  Therefore, in theory, those spans should have INTERNAL span created by DispatcherHandlerInstrumentation
  as their parent. But there is a difference how Spring WebFlux handles functional endpoints
  (created in server.SpringWebFluxTestApplication.greetRouterFunction) and annotated endpoints
  (created in server.TestController).
  In the former case org.springframework.web.reactive.function.server.support.HandlerFunctionAdapter.handle
  calls handler function directly. Thus "tracedMethod" span below has INTERNAL handler span as its parent.
  In the latter case org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter.handle
  merely wraps handler call into Mono and thus actual invocation of handler function happens later,
  when INTERNAL handler span has already finished. Thus, "tracedMethod" has SERVER Netty span as its parent.
   */
  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("provideAsyncHandlerFuncParameters")
  void createSpanDuringHandlerFunctionTest(Parameter parameter) {
    AggregatedHttpResponse response = client.get(parameter.urlPath).aggregate().join();

    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.contentUtf8()).isEqualTo(parameter.expectedResponseBody);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET " + parameter.urlPathWithVariables)
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, parameter.urlPath),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 200),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, parameter.urlPathWithVariables),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span -> {
                  if (parameter.annotatedMethod == null) {
                    // Functional API
                    assertThat(trace.getSpan(1).getName())
                        .contains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle");
                  } else {
                    // Annotation API
                    span.hasName(
                        TestController.class.getSimpleName() + "." + parameter.annotatedMethod);
                  }
                  span.hasKind(SpanKind.INTERNAL)
                      .hasParent(trace.getSpan(0))
                      .hasAttributesSatisfyingExactly(
                          satisfies(
                              stringKey("spring-webflux.handler.type"),
                              parameter.annotatedMethod == null
                                  ? val -> val.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
                                  : val -> val.isEqualTo(TestController.class.getName())));
                },
                span ->
                    span.hasName("tracedMethod")
                        .hasParent(trace.getSpan(parameter.annotatedMethod != null ? 0 : 1))
                        .hasTotalAttributeCount(0)));
  }

  private static Stream<Arguments> provideAsyncHandlerFuncParameters() {
    return Stream.of(
        Arguments.of(
            named(
                "functional API traced method",
                new Parameter(
                    "/greet-traced-method/5",
                    "/greet-traced-method/{id}",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " 5"))),
        Arguments.of(
            named(
                "annotation API traced method",
                new Parameter(
                    "/foo-traced-method/8",
                    "/foo-traced-method/{id}",
                    "getTracedMethod",
                    new FooModel(8L, "tracedMethod").toString()))));
  }

  @Test
  void get404Test() {
    AggregatedHttpResponse response = client.get("/notfoundgreet").aggregate().join();

    assertThat(response.status().code()).isEqualTo(404);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET /**")
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasStatus(StatusData.unset())
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, "/notfoundgreet"),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 404),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, "/**"),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span ->
                    span.hasName("ResourceWebHandler.handle")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(0))
                        .hasStatus(StatusData.error())
                        .hasEventsSatisfyingExactly(
                            event ->
                                event
                                    .hasName(EXCEPTION_EVENT_NAME)
                                    .hasAttributesSatisfyingExactly(
                                        equalTo(
                                            EXCEPTION_TYPE,
                                            "org.springframework.web.server.ResponseStatusException"),
                                        satisfies(
                                            EXCEPTION_MESSAGE,
                                            val ->
                                                val.containsAnyOf(
                                                    "Response status 404", "404 NOT_FOUND")),
                                        satisfies(
                                            EXCEPTION_STACKTRACE,
                                            val -> val.isInstanceOf(String.class))))
                        .hasAttributesSatisfyingExactly(
                            equalTo(
                                stringKey("spring-webflux.handler.type"),
                                "org.springframework.web.reactive.resource.ResourceWebHandler"))));
  }

  @Test
  void basicPostTest() {
    String echoString = "TEST";
    AggregatedHttpResponse response = client.post("/echo", echoString).aggregate().join();

    assertThat(response.status().code()).isEqualTo(202);
    assertThat(response.contentUtf8()).isEqualTo(echoString);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("POST /echo")
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, "/echo"),
                            equalTo(HTTP_METHOD, "POST"),
                            equalTo(HTTP_STATUS_CODE, 202),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, "/echo"),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span ->
                    span.hasName(EchoHandlerFunction.class.getSimpleName() + ".handle")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            satisfies(
                                stringKey("spring-webflux.handler.type"),
                                val -> val.contains(EchoHandlerFunction.class.getName()))),
                span ->
                    span.hasName("echo").hasParent(trace.getSpan(1)).hasTotalAttributeCount(0)));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("provideBadEndpointParameters")
  void getToBadEndpointTest(Parameter parameter) {
    AggregatedHttpResponse response = client.get(parameter.urlPath).aggregate().join();

    assertThat(response.status().code()).isEqualTo(500);
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET " + parameter.urlPathWithVariables)
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasStatus(StatusData.error())
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, parameter.urlPath),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 500),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, parameter.urlPathWithVariables),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span -> {
                  if (parameter.annotatedMethod == null) {
                    // Functional API
                    assertThat(trace.getSpan(1).getName())
                        .contains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle");
                  } else {
                    // Annotation API
                    span.hasName(
                        TestController.class.getSimpleName() + "." + parameter.annotatedMethod);
                  }
                  span.hasKind(SpanKind.INTERNAL)
                      .hasParent(trace.getSpan(0))
                      .hasStatus(StatusData.error())
                      .hasEventsSatisfyingExactly(
                          event ->
                              event
                                  .hasName(EXCEPTION_EVENT_NAME)
                                  .hasAttributesSatisfyingExactly(
                                      equalTo(EXCEPTION_TYPE, "java.lang.IllegalStateException"),
                                      equalTo(EXCEPTION_MESSAGE, "bad things happen"),
                                      satisfies(
                                          EXCEPTION_STACKTRACE,
                                          val -> val.isInstanceOf(String.class))))
                      .hasAttributesSatisfyingExactly(
                          satisfies(
                              stringKey("spring-webflux.handler.type"),
                              parameter.annotatedMethod == null
                                  ? val -> val.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
                                  : val -> val.isEqualTo(TestController.class.getName())));
                }));
  }

  private static Stream<Arguments> provideBadEndpointParameters() {
    return Stream.of(
        Arguments.of(
            named(
                "functional API fail fast",
                new Parameter("/greet-failfast/1", "/greet-failfast/{id}", null, null))),
        Arguments.of(
            named(
                "functional API fail Mono",
                new Parameter("/greet-failmono/1", "/greet-failmono/{id}", null, null))),
        Arguments.of(
            named(
                "annotation API fail fast",
                new Parameter("/foo-failfast/1", "/foo-failfast/{id}", "getFooFailFast", null))),
        Arguments.of(
            named(
                "annotation API fail Mono",
                new Parameter("/foo-failmono/1", "/foo-failmono/{id}", "getFooFailMono", null))));
  }

  @Test
  void redirectTest() {
    AggregatedHttpResponse response = client.get("/double-greet-redirect").aggregate().join();

    assertThat(response.status().code()).isEqualTo(200);
    testing.waitAndAssertTraces(
        trace ->
            // TODO: why order of spans is different in these traces?
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET /double-greet-redirect")
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, "/double-greet-redirect"),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 307),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, "/double-greet-redirect"),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span ->
                    span.hasName("RedirectComponent$$Lambda$.handle")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            satisfies(
                                stringKey("spring-webflux.handler.type"),
                                val -> val.startsWith("server.RedirectComponent$$Lambda$")))),
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET /double-greet")
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, "/double-greet"),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 200),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, "/double-greet"),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span -> {
                  assertThat(trace.getSpan(1).getName())
                      .contains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle");
                  span.hasKind(SpanKind.INTERNAL)
                      .hasParent(trace.getSpan(0))
                      .hasAttributesSatisfyingExactly(
                          satisfies(
                              stringKey("spring-webflux.handler.type"),
                              val -> val.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)));
                }));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("provideMultipleDelayingRouteParameters")
  void multipleGetsToDelayingRoute(Parameter parameter) {
    int requestsCount = 50;

    List<AggregatedHttpResponse> responses =
        IntStream.rangeClosed(0, requestsCount - 1)
            .mapToObj(n -> client.get(parameter.urlPath).aggregate().join())
            .collect(Collectors.toList());

    assertThat(responses)
        .extracting(AggregatedHttpResponse::status)
        .extracting(HttpStatus::code)
        .containsOnly(200);
    assertThat(responses)
        .extracting(AggregatedHttpResponse::contentUtf8)
        .containsOnly(parameter.expectedResponseBody);

    Consumer<TraceAssert> traceAssertion =
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET " + parameter.urlPathWithVariables)
                        .hasKind(SpanKind.SERVER)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            equalTo(NET_TRANSPORT, IP_TCP),
                            equalTo(NET_SOCK_PEER_ADDR, "127.0.0.1"),
                            satisfies(NET_SOCK_PEER_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(NET_SOCK_HOST_ADDR, "127.0.0.1"),
                            equalTo(NET_HOST_NAME, "localhost"),
                            satisfies(NET_HOST_PORT, val -> val.isInstanceOf(Long.class)),
                            equalTo(HTTP_TARGET, parameter.urlPath),
                            equalTo(HTTP_METHOD, "GET"),
                            equalTo(HTTP_STATUS_CODE, 200),
                            equalTo(HTTP_SCHEME, "http"),
                            equalTo(HTTP_FLAVOR, "1.1"),
                            satisfies(HTTP_USER_AGENT, val -> val.isInstanceOf(String.class)),
                            equalTo(HTTP_ROUTE, parameter.urlPathWithVariables),
                            satisfies(
                                HTTP_REQUEST_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull())),
                            satisfies(
                                HTTP_RESPONSE_CONTENT_LENGTH,
                                val ->
                                    val.satisfiesAnyOf(
                                        v -> assertThat(v).isInstanceOf(Long.class),
                                        v -> assertThat(v).isNull()))),
                span -> {
                  if (parameter.annotatedMethod == null) {
                    // Functional API
                    assertThat(trace.getSpan(1).getName())
                        .contains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle");
                  } else {
                    // Annotation API
                    span.hasName(
                        TestController.class.getSimpleName() + "." + parameter.annotatedMethod);
                  }
                  span.hasKind(SpanKind.INTERNAL)
                      .hasParent(trace.getSpan(0))
                      .hasAttributesSatisfyingExactly(
                          satisfies(
                              stringKey("spring-webflux.handler.type"),
                              parameter.annotatedMethod == null
                                  ? val -> val.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
                                  : val -> val.isEqualTo(TestController.class.getName())));
                });

    testing.waitAndAssertTraces(Collections.nCopies(requestsCount, traceAssertion));
  }

  private static Stream<Arguments> provideMultipleDelayingRouteParameters() {
    return Stream.of(
        Arguments.of(
            named(
                "functional API delayed response",
                new Parameter(
                    "/greet-delayed",
                    "/greet-delayed",
                    null,
                    SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE))),
        Arguments.of(
            named(
                "annotation API delayed response",
                new Parameter(
                    "/foo-delayed",
                    "/foo-delayed",
                    "getFooDelayed",
                    new FooModel(3L, "delayed").toString()))));
  }

  private static class Parameter {
    Parameter(
        String urlPath,
        String urlPathWithVariables,
        String annotatedMethod,
        String expectedResponseBody) {
      this.urlPath = urlPath;
      this.urlPathWithVariables = urlPathWithVariables;
      this.annotatedMethod = annotatedMethod;
      this.expectedResponseBody = expectedResponseBody;
    }

    public final String urlPath;
    public final String urlPathWithVariables;
    public final String annotatedMethod;
    public final String expectedResponseBody;
  }
}
