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

import com.google.common.base.Preconditions;
import com.nordstrom.xrpc.server.http.Route;
import com.nordstrom.xrpc.server.http.RoutePath;
import io.netty.handler.codec.http.HttpMethod;

/** Interface for constructing routes. */
public interface Routes extends Iterable<Route> {
  /**
   * Binds a handler for GET requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a GET handler for the route.
   */
  default Routes get(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.GET);
  }

  /**
   * Binds a handler for POST requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a POST handler for the route.
   */
  default Routes post(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.POST);
  }

  /**
   * Binds a handler for PUT requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a PUT handler for the route.
   */
  default Routes put(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.PUT);
  }

  /**
   * Binds a handler for DELETE requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a DELETE handler for the route.
   */
  default Routes delete(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.DELETE);
  }

  /**
   * Binds a handler for HEAD requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a HEAD handler for the route.
   */
  default Routes head(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.HEAD);
  }

  /**
   * Binds a handler for OPTIONS requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a OPTIONS handler for the route.
   */
  default Routes options(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.OPTIONS);
  }

  /**
   * Binds a handler for PATCH requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a PATCH handler for the route.
   */
  default Routes patch(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.PATCH);
  }

  /**
   * Binds a handler for TRACE requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a TRACE handler for the route.
   */
  default Routes trace(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.TRACE);
  }

  /**
   * Binds a handler for CONNECT requests to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a CONNECT handler for the route.
   */
  default Routes connect(String route, Handler handler) {
    return addRoute(route, handler, HttpMethod.CONNECT);
  }

  /**
   * Binds a handler for the given method to the given route.
   *
   * @return this builder
   * @throws IllegalArgumentException if either the route or handler is null; if the route is empty;
   *     or if there is already a handler for the given method + route pair.
   */
  default Routes addRoute(String routePattern, Handler handler, HttpMethod method) {
    Preconditions.checkArgument(routePattern != null, "routePattern must not be null");
    Preconditions.checkArgument(!routePattern.isEmpty(), "routePattern must not be empty");
    Preconditions.checkArgument(handler != null, "handler must not be null");
    Preconditions.checkArgument(method != null, "method must not be null");

    RoutePath routePath = RoutePath.build(routePattern);

    addRoute(new Route(method, routePath, handler));
    return this;
  }

  /**
   * Adds given route to this route collection.
   *
   * @return this builder
   */
  Routes addRoute(Route route);

  /**
   * Adds a collection of routes from another Routes provider.
   *
   * @param routes routes provider.
   * @return this builder
   */
  default Routes addRoutes(Routes routes) {
    for (Route route : routes) {
      addRoute(route);
    }
    return this;
  }

  /**
   * Adds all routes from a Service to this route collection.
   *
   * @param service add routes from this Service
   * @return this builder
   */
  default Routes addService(Service service) {
    return addRoutes(service.routes());
  }
}
