/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source.legacy;

import static java.util.Collections.emptyList;

import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.tx.TransactionException;
import org.mule.runtime.core.internal.execution.NotificationFunction;
import org.mule.runtime.extension.api.runtime.source.SourceCallbackContext;
import org.mule.runtime.module.extension.internal.runtime.notification.legacy.LegacyNotificationActionDefinitionAdapter;
import org.mule.runtime.module.extension.internal.runtime.source.SourceCallbackContextAdapter;
import org.mule.runtime.module.extension.internal.runtime.source.trace.SourceDistributedSourceTraceContext;
import org.mule.sdk.api.notification.NotificationActionDefinition;
import org.mule.sdk.api.runtime.source.SourceCallback;
import org.mule.sdk.api.runtime.source.DistributedTraceContextManager;
import org.mule.sdk.api.tx.TransactionHandle;

import java.util.List;
import java.util.Optional;

/**
 * Adapts a legacy {@link SourceCallbackContext} into a {@link SourceCallbackContextAdapter}
 *
 * @since 4.4.0
 */
public class SdkSourceCallBackContextAdapter implements SourceCallbackContextAdapter {

  private final SourceCallbackContext delegate;
  private DistributedTraceContextManager distributedSourceTraceContext = new SourceDistributedSourceTraceContext();

  public SdkSourceCallBackContextAdapter(SourceCallbackContext delegate) {
    this.delegate = delegate;
  }

  @Override
  public TransactionHandle bindConnection(Object o) throws ConnectionException, TransactionException {
    return delegate.bindConnection(o);
  }

  @Override
  public <T> T getConnection() throws IllegalStateException {
    return delegate.getConnection();
  }

  @Override
  public TransactionHandle getTransactionHandle() {
    return delegate.getTransactionHandle();
  }

  @Override
  public boolean hasVariable(String s) {
    return delegate.hasVariable(s);
  }

  @Override
  public <T> Optional<T> getVariable(String s) {
    return delegate.getVariable(s);
  }

  @Override
  public void addVariable(String s, Object o) {
    delegate.addVariable(s, o);
  }

  @Override
  public void setCorrelationId(String s) {
    delegate.setCorrelationId(s);
  }

  @Override
  public Optional<String> getCorrelationId() {
    return delegate.getCorrelationId();
  }

  @Override
  public <T, A> SourceCallback<T, A> getSourceCallback() {
    return new SdkSourceCallbackAdapter<>(delegate.getSourceCallback());
  }

  @Override
  public void fireOnHandle(NotificationActionDefinition<?> notificationActionDefinition, TypedValue<?> typedValue) {
    delegate.fireOnHandle(new LegacyNotificationActionDefinitionAdapter(notificationActionDefinition), typedValue);
  }

  @Override
  public DistributedTraceContextManager getDistributedSourceTraceContext() {
    if (delegate instanceof LegacySourceCallbackContextAdapter) {
      return ((LegacySourceCallbackContextAdapter) delegate).getDistributedSourceTraceContext();
    }
    return distributedSourceTraceContext;
  }

  @Override
  public void releaseConnection() {
    if (delegate instanceof AugmentedLegacySourceCallbackContext) {
      ((AugmentedLegacySourceCallbackContext) delegate).releaseConnection();
    }
  }

  @Override
  public void dispatched() {
    if (delegate instanceof AugmentedLegacySourceCallbackContext) {
      ((AugmentedLegacySourceCallbackContext) delegate).dispatched();
    }
  }

  @Override
  public List<NotificationFunction> getNotificationsFunctions() {
    if (delegate instanceof AugmentedLegacySourceCallbackContext) {
      return ((AugmentedLegacySourceCallbackContext) delegate).getNotificationsFunctions();
    }
    return emptyList();
  }
}
