/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.result;

import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.module.extension.api.runtime.privileged.EventedResult;
import org.mule.runtime.module.extension.api.runtime.privileged.ExecutionContextAdapter;

/**
 * An implementation of {@link ReturnDelegate} which allows setting the response value into the {@link Message} that will continue
 * through the pipeline.
 *
 * @since 4.0
 */
public final class ValueReturnDelegate extends AbstractReturnDelegate {

  /**
   * {@inheritDoc}
   */
  public ValueReturnDelegate(ComponentModel componentModel, MuleContext muleContext) {
    super(componentModel, muleContext);
  }

  /**
   * If the {@code value} is a {@link Message}, then a new one is created merging the contents of the returned value with the ones
   * of the input message. The merging criteria is as follows:
   * <li>
   * <ul>
   * The {@code value}'s payload and DataType is set on the output message
   * </ul>
   * <ul>
   * If the {@code value} has a not {@code null} output for {@link Message#getAttributes()}, then that value is set on the
   * outbound message. Otherwise, whatever value the input message had is maintained
   * </ul>
   * </li>
   * <p>
   * If the {@code value} is of any other type, then it's set as the payload of the outgoing message {@inheritDoc}
   */
  @Override
  public CoreEvent asReturnValue(Object value, ExecutionContextAdapter operationContext) {
    if (value instanceof CoreEvent) {
      return (CoreEvent) value;
    }

    return value instanceof EventedResult
        ? ((EventedResult) value).getEvent()
        : CoreEvent.builder(operationContext.getEvent())
            .securityContext(operationContext.getSecurityContext())
            .message(toMessage(value, operationContext)).build();
  }
}
