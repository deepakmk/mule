/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.tracer.impl.clock;

import static org.mule.runtime.tracer.impl.clock.SystemNanoTimeClock.getInstance;

/**
 * A clock used for tracing and measure the duration of {@link org.mule.runtime.tracer.api.span.InternalSpan}.
 *
 * @since 4.5.0
 */
public interface Clock {

  /**
   * @return default implementation of clock.
   */
  static Clock getDefault() {
    return getInstance();
  }

  /**
   * @return the current epoch timestamp in nanos from this clock.
   */
  long now();
}
