/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.core.internal.routing;

import static java.util.Collections.emptyList;
import static org.mule.runtime.core.api.config.i18n.CoreMessages.cannotCopyStreamPayload;
import static org.mule.runtime.core.internal.routing.ForkJoinStrategy.RoutingPair.of;
import static reactor.core.publisher.Flux.fromIterable;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.tracing.customization.ComponentExecutionInitialSpanInfo;
import org.mule.runtime.core.internal.routing.forkjoin.CollectMapForkJoinStrategyFactory;
import org.mule.runtime.core.privileged.processor.Router;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChain;

import java.util.List;
import java.util.function.Consumer;

import org.mule.runtime.core.privileged.profiling.tracing.InitialSpanInfoAware;
import org.reactivestreams.Publisher;

/**
 * <p>
 * The <code>Scatter-Gather</code> router will broadcast copies of the current message to every route in parallel subject to any
 * limitation in concurrency that has been configured
 * <p>
 * For advanced use cases, a custom {@link ForkJoinStrategyFactory} can be applied to customize the logic used to aggregate the
 * route responses back into one single Event.
 * <p>
 * <b>EIP Reference:</b> <a href="http://www.eaipatterns.com/BroadcastAggregate.html"<a/>
 * </p>
 *
 * @since 3.5.0
 */
public class ScatterGatherRouter extends AbstractForkJoinRouter implements Router {

  private List<MessageProcessorChain> routes = emptyList();

  @Override
  protected Consumer<CoreEvent> onEvent() {
    return event -> validateMessageIsNotConsumable(event.getMessage());
  }

  @Override
  protected Publisher<ForkJoinStrategy.RoutingPair> getRoutingPairs(CoreEvent event) {
    return fromIterable(routes).map(route -> of(event, route));
  }

  @Override
  protected List<MessageProcessorChain> getOwnedObjects() {
    return routes;
  }

  public void setRoutes(List<MessageProcessorChain> routes) {
    this.routes = routes;
    for (MessageProcessorChain route : routes) {
      if (route instanceof InitialSpanInfoAware) {
        ((InitialSpanInfoAware) route)
            .setInitialSpanInfo(new ComponentExecutionInitialSpanInfo(this, ":route"));
      }
    }
  }

  @Override
  protected boolean isDelayErrors() {
    return true;
  }

  @Override
  protected int getDefaultMaxConcurrency() {
    return routes.size();
  }

  @Override
  protected ForkJoinStrategyFactory getDefaultForkJoinStrategyFactory() {
    return new CollectMapForkJoinStrategyFactory();
  }

  /**
   * Validates that the payload is not consumable so it can be copied.
   * <p>
   * If validation fails then throws a MessagingException
   *
   * @param message
   * @throws MuleException if the payload is consumable
   */
  public static void validateMessageIsNotConsumable(Message message) {
    if (message.getPayload().getDataType().isStreamType()) {
      throw new MuleRuntimeException(cannotCopyStreamPayload(message.getPayload().getDataType().getType().getName()));
    }
  }
}
