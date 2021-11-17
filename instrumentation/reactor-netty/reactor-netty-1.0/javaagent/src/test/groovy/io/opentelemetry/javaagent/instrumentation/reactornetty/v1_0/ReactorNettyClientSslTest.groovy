/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.reactornetty.v1_0

import io.netty.handler.ssl.SslContextBuilder
import io.opentelemetry.instrumentation.test.AgentInstrumentationSpecification
import io.opentelemetry.instrumentation.testing.junit.http.HttpClientTestServer
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.SslProvider
import spock.lang.Shared

import javax.net.ssl.SSLHandshakeException

import static io.opentelemetry.api.trace.SpanKind.CLIENT
import static io.opentelemetry.api.trace.SpanKind.INTERNAL
import static io.opentelemetry.api.trace.SpanKind.SERVER
import static io.opentelemetry.api.trace.StatusCode.ERROR
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.NetTransportValues.IP_TCP

class ReactorNettyClientSslTest extends AgentInstrumentationSpecification {

  @Shared
  private HttpClientTestServer server

  def setupSpec() {
    server = new HttpClientTestServer(openTelemetry)
    server.start()
  }

  def cleanupSpec() {
    server.stop()
  }

  def "should fail SSL handshake"() {
    given:
    def httpClient = createHttpClient(["SSLv3"])

    when:
    def responseMono = httpClient.get().uri("https://localhost:${server.httpsPort()}/success")
      .responseSingle { resp, content ->
        // Make sure to consume content since that's when we close the span.
        content.map { resp }
      }

    runWithSpan("parent") {
      responseMono.block()
    }

    then:
    Throwable thrownException = thrown()

    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          name "parent"
          status ERROR
          errorEvent(thrownException.class, thrownException.message)
        }
        span(1) {
          name "RESOLVE"
          kind INTERNAL
          childOf span(0)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" IP_TCP
            "${SemanticAttributes.NET_PEER_NAME.key}" "localhost"
            "${SemanticAttributes.NET_PEER_PORT.key}" server.httpsPort()
          }
        }
        span(2) {
          name "CONNECT"
          kind INTERNAL
          childOf span(0)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" IP_TCP
            "${SemanticAttributes.NET_PEER_NAME.key}" "localhost"
            "${SemanticAttributes.NET_PEER_PORT.key}" server.httpsPort()
            "${SemanticAttributes.NET_PEER_IP.key}" { it == null || it == "127.0.0.1" }
          }
        }
        span(3) {
          name "SSL handshake"
          kind INTERNAL
          childOf span(0)
          status ERROR
          // netty swallows the exception, it doesn't make any sense to hard-code the message
          errorEventWithAnyMessage(SSLHandshakeException)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" IP_TCP
            "${SemanticAttributes.NET_PEER_NAME.key}" "localhost"
            "${SemanticAttributes.NET_PEER_PORT.key}" server.httpsPort()
            "${SemanticAttributes.NET_PEER_IP.key}" { it == null || it == "127.0.0.1" }
          }
        }
      }
    }
  }

  def "should successfully establish SSL handshake"() {
    given:
    def httpClient = createHttpClient()

    when:
    def responseMono = httpClient.get().uri("https://localhost:${server.httpsPort()}/success")
      .responseSingle { resp, content ->
        // Make sure to consume content since that's when we close the span.
        content.map { resp }
      }

    runWithSpan("parent") {
      responseMono.block()
    }

    then:
    assertTraces(1) {
      trace(0, 6) {
        span(0) {
          name "parent"
        }
        span(1) {
          name "RESOLVE"
          kind INTERNAL
          childOf span(0)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" IP_TCP
            "${SemanticAttributes.NET_PEER_NAME.key}" "localhost"
            "${SemanticAttributes.NET_PEER_PORT.key}" server.httpsPort()
          }
        }
        span(2) {
          name "CONNECT"
          kind INTERNAL
          childOf span(0)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" IP_TCP
            "${SemanticAttributes.NET_PEER_NAME.key}" "localhost"
            "${SemanticAttributes.NET_PEER_PORT.key}" server.httpsPort()
            "${SemanticAttributes.NET_PEER_IP.key}" { it == null || it == "127.0.0.1" }
          }
        }
        span(3) {
          name "SSL handshake"
          kind INTERNAL
          childOf span(0)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" IP_TCP
            "${SemanticAttributes.NET_PEER_NAME.key}" "localhost"
            "${SemanticAttributes.NET_PEER_PORT.key}" server.httpsPort()
            "${SemanticAttributes.NET_PEER_IP.key}" { it == null || it == "127.0.0.1" }
          }
        }
        span(4) {
          name "HTTP GET"
          kind CLIENT
          childOf(span(0))
        }
        span(5) {
          name "test-http-server"
          kind SERVER
          childOf(span(4))
        }
      }
    }
  }

  private static HttpClient createHttpClient(List<String> enabledProtocols = null) {
    def sslContext = SslContextBuilder.forClient()
    if (enabledProtocols != null) {
      sslContext = sslContext.protocols(enabledProtocols)
    }
    def sslProvider = SslProvider.builder()
      .sslContext(sslContext.build())
      .build()
    HttpClient.create().secure(sslProvider)
  }
}