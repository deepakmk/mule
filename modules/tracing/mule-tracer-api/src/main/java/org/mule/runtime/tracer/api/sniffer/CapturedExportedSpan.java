/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.tracer.api.sniffer;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates data corresponding to a captured exported span. This is only used for testing purposes and is not exposed as
 * general API.
 *
 * @see ExportedSpanSniffer
 *
 * @since 4.5.0
 */
public interface CapturedExportedSpan {

  /**
   * @return the name of the exported span.
   */
  String getName();

  /**
   * @return the parent span id.
   */
  String getParentSpanId();

  /**
   * @return the span id.
   */
  String getSpanId();

  /**
   * @return the trace id.
   */
  String getTraceId();

  /**
   * @return the span attributes
   */
  Map<String, String> getAttributes();

  /**
   * @return the service name
   */
  String getServiceName();

  /**
   * @return the span kind name
   */
  String getSpanKindName();

  List<CapturedEventData> getEvents();

  /**
   * @return True if the status of the Span is ERROR.
   */
  boolean hasErrorStatus();

  /**
   * @return the start span nanos.
   */
  long getStartEpochSpanNanos();

  /**
   * @return the end span nanos.
   */
  long getEndSpanEpochNanos();
}
