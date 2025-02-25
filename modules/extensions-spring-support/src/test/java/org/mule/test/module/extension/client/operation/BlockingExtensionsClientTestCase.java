/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.extension.client.operation;

import static org.mule.test.allure.AllureConstants.ExtensionsClientFeature.EXTENSIONS_CLIENT;
import static org.mule.test.allure.AllureConstants.ExtensionsClientFeature.ExtensionsClientStory.BLOCKING_CLIENT;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.extension.api.client.OperationParameters;
import org.mule.runtime.extension.api.runtime.operation.Result;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

@Feature(EXTENSIONS_CLIENT)
@Story(BLOCKING_CLIENT)
public class BlockingExtensionsClientTestCase extends ExtensionsClientTestCase {

  @Override
  <T, A> Result<T, A> doExecute(String extension, String operation, OperationParameters params)
      throws MuleException {
    return client.execute(extension, operation, params);
  }
}
