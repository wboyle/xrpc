/*
 * Copyright 2018 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nordstrom.xrpc.server;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.JsonFormat;
import com.nordstrom.xrpc.XConfig;
import com.nordstrom.xrpc.encoding.Decoders;
import com.nordstrom.xrpc.encoding.Encoders;
import com.nordstrom.xrpc.encoding.JsonDecoder;
import com.nordstrom.xrpc.encoding.JsonEncoder;
import com.nordstrom.xrpc.encoding.ProtoDecoder;
import com.nordstrom.xrpc.encoding.ProtoDefaultInstances;
import com.nordstrom.xrpc.encoding.ProtoEncoder;
import com.nordstrom.xrpc.server.http.Route;
import com.nordstrom.xrpc.server.tls.Tls;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

/** An xprc server. */
@Slf4j
@Accessors(fluent = true)
public class Server implements Routes {
  private final XConfig config;
  private final Tls tls;

  /* Context builder. */
  private final ServerContext.Builder contextBuilder;

  @Getter private final MetricRegistry metricRegistry = new MetricRegistry();
  @Getter private final RouteBuilder routeBuilder = new RouteBuilder();

  /** Configured port. */
  @Getter private final int port;

  @Getter private Channel channel;
  @Getter private final HealthCheckRegistry healthCheckRegistry;

  /** Construct a server with the default configuration. */
  public Server() {
    this(new XConfig(ConfigFactory.empty()), -1);
  }

  /**
   * Construct a server with configured port. If the port is &lt; 0, the port will be taken from
   * configuration. If it is 0, the system will pick up an ephemeral port.
   */
  public Server(int port) {
    this(new XConfig(ConfigFactory.empty()), port);
  }

  /** Construct a server with the given configuration. * */
  public Server(Config config) {
    this(new XConfig(config), -1);
  }

  /**
   * Construct a server with the given configuration and port. If the port is &lt; 0, the port will
   * be taken from configuration. If it is 0, the system will pick up an ephemeral port.
   */
  public Server(Config config, int port) {
    this(new XConfig(config), port);
  }

  @Deprecated()
  public Server(XConfig config) {
    this(config, -1);
  }

  private Server(XConfig config, int port) {
    this.config = config;
    this.port = port >= 0 ? port : config.port();
    this.tls = new Tls(config.tlsConfig());
    this.healthCheckRegistry = new HealthCheckRegistry(config.asyncHealthCheckThreadCount());

    // This adds support for normal constructor binding.
    // See: https://github.com/FasterXML/jackson-modules-java8/tree/master/parameter-names
    ObjectMapper mapper =
        new ObjectMapper().registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));

    // Json encoder for Proto
    JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();

    // Default instances for protobuf generated classes.
    ProtoDefaultInstances protoDefaultInstances = new ProtoDefaultInstances();

    this.contextBuilder =
        ServerContext.builder()
            .requestMeter(metricRegistry.meter("requests"))
            .encoders(
                Encoders.builder()
                    .defaultContentType(config.defaultContentType())
                    .encoder(new JsonEncoder(mapper, printer))
                    // TODO (AD): For now we won't support text/plain encoding.
                    // Leaving this here as a placeholder.
                    // .encoder(new TextEncoder())
                    .encoder(new ProtoEncoder())
                    .build())
            .decoders(
                Decoders.builder()
                    .defaultContentType(config.defaultContentType())
                    .decoder(new JsonDecoder(mapper, protoDefaultInstances))
                    .decoder(new ProtoDecoder(protoDefaultInstances))
                    .build())
            .exceptionHandler(ResponseFactory::exception);

    addResponseCodeMeters(contextBuilder, metricRegistry);
  }

  @Override
  public Routes addRoute(Route route) {
    routeBuilder.addRoute(route);
    // Return a this-reference to adhere to contract.
    return this;
  }

  @Override
  public Iterator<Route> iterator() {
    return routeBuilder.iterator();
  }

  /** Adds a meter for all HTTP response codes to the given ServerContext. */
  @VisibleForTesting
  static void addResponseCodeMeters(
      ServerContext.Builder contextBuilder, MetricRegistry metricRegistry) {
    Map<HttpResponseStatus, String> namesByCode = new HashMap<>();
    namesByCode.put(HttpResponseStatus.OK, "ok");
    namesByCode.put(HttpResponseStatus.CREATED, "created");
    namesByCode.put(HttpResponseStatus.ACCEPTED, "accepted");
    namesByCode.put(HttpResponseStatus.NO_CONTENT, "noContent");
    namesByCode.put(HttpResponseStatus.BAD_REQUEST, "badRequest");
    namesByCode.put(HttpResponseStatus.UNAUTHORIZED, "unauthorized");
    namesByCode.put(HttpResponseStatus.FORBIDDEN, "forbidden");
    namesByCode.put(HttpResponseStatus.NOT_FOUND, "notFound");
    // Note that the HTTP RFC name is "Payload Too Large"; REQUEST_ENTITY_TOO_LARGE is an old name.
    namesByCode.put(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "payloadTooLarge");
    namesByCode.put(HttpResponseStatus.TOO_MANY_REQUESTS, "tooManyRequests");
    namesByCode.put(HttpResponseStatus.INTERNAL_SERVER_ERROR, "serverError");

    // Create the proper metrics containers.
    for (Map.Entry<HttpResponseStatus, String> entry : namesByCode.entrySet()) {
      String meterName = MetricRegistry.name("responseCodes", entry.getValue());
      contextBuilder.meterByStatusCode(entry.getKey(), metricRegistry.meter(meterName));
    }
  }

  /**
   * Set the custom exception handler. This handler is called to handle any exceptions thrown from
   * route handlers. This replaces any other exception handlers previously set.
   */
  public void exceptionHandler(ExceptionHandler handler) {
    contextBuilder.exceptionHandler(handler);
  }

  public void addHealthCheck(String name, HealthCheck check) {
    healthCheckRegistry.register(name, check);
  }

  /**
   * Builds an initializer that sets up the server pipeline, override this method to customize your
   * pipeline.
   *
   * @param state a value object with server wide state
   * @return a ChannelInitializer that builds this servers ChannelPipeline
   */
  public ChannelInitializer<Channel> initializer(State state) {
    return new ServerChannelInitializer(state);
  }

  /**
   * The listenAndServe method is the primary entry point for the server and should only be called
   * once and only from the main thread. Routes added after this is invoked will not be served.
   *
   * @throws IOException throws in the event the network services, as specified, cannot be accessed
   */
  public void listenAndServe() throws IOException {
    // Finalize the routes this serves.
    if (config.adminRoutesEnableInfo()) {
      AdminHandlers.registerInfoAdminRoutes(this);
    }
    if (config.adminRoutesEnableUnsafe()) {
      AdminHandlers.registerUnsafeAdminRoutes(this);
    }

    contextBuilder.routes(routeBuilder.compile(metricRegistry));

    ServerContext ctx = contextBuilder.build();

    State state =
        State.builder()
            .config(config)
            .globalConnectionLimiter(
                new ConnectionLimiter(
                    metricRegistry, config.maxConnections())) // All endpoints for a given service
            .rateLimiter(new ServiceRateLimiter(metricRegistry, config, ctx))
            .whiteListFilter(new WhiteListFilter(metricRegistry, config.ipWhiteList()))
            .blackListFilter(new BlackListFilter(metricRegistry, config.ipBlackList()))
            .firewall(new Firewall(metricRegistry))
            .tls(tls)
            .h1h2(
                new Http2OrHttpHandler(
                    new UrlRouter(), ctx, config.corsConfig(), config.maxPayloadBytes()))
            .build();

    ServerBootstrap b =
        XrpcBootstrapFactory.buildBootstrap(
            config.bossThreadCount(), config.workerThreadCount(), config.workerNameFormat());

    b.childHandler(initializer(state));

    InetSocketAddress address = new InetSocketAddress(port);
    ChannelFuture future = b.bind(address);

    try {
      // Build out the loggers that are specified in the config

      if (config.slf4jReporter()) {
        final Slf4jReporter slf4jReporter =
            Slf4jReporter.forRegistry(metricRegistry)
                .outputTo(LoggerFactory.getLogger(Server.class))
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        slf4jReporter.start(config.slf4jReporterPollingRate(), TimeUnit.SECONDS);
      }

      if (config.jmxReporter()) {
        final JmxReporter jmxReporter = JmxReporter.forRegistry(metricRegistry).build();
        jmxReporter.start();
      }

      if (config.consoleReporter()) {
        final ConsoleReporter consoleReporter =
            ConsoleReporter.forRegistry(metricRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        consoleReporter.start(config.consoleReporterPollingRate(), TimeUnit.SECONDS);
      }

      future.await();

    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for bind");
    }

    if (!future.isSuccess()) {
      throw new IOException("Failed to bind", future.cause());
    }

    channel = future.channel();
    InetSocketAddress actualAddress = (InetSocketAddress) channel.localAddress();
    log.info("Listening at {}", actualAddress.getAddress().getCanonicalHostName());
  }

  public String localEndpoint() {
    InetSocketAddress actualAddress = (InetSocketAddress) channel.localAddress();
    return String.format("https://127.0.0.1:%d", actualAddress.getPort());
  }

  public void shutdown() {
    if (channel == null || !channel.isOpen()) {
      return;
    }

    channel
        .close()
        .addListener(
            future -> {
              if (!future.isSuccess()) {
                log.warn("Error shutting down server", future.cause());
              }
              synchronized (Server.this) {
                // TODO(JR): We should probably be more thoughtful here.
                shutdown();
              }
            });
  }
}
