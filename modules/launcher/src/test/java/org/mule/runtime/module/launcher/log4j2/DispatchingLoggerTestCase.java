/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.launcher.log4j2;

import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.test.allure.AllureConstants.Logging.LOGGING;
import static org.mule.test.allure.AllureConstants.Logging.LoggingStory.CONTEXT_FACTORY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mule.runtime.module.artifact.api.classloader.RegionClassLoader;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.selector.ContextSelector;
import org.apache.logging.log4j.message.MessageFactory;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

@SmallTest
@Feature(LOGGING)
@Story(CONTEXT_FACTORY)
public class DispatchingLoggerTestCase extends AbstractMuleTestCase {

  private static final String LOGGER_NAME = DispatchingLoggerTestCase.class.getName();

  private static final String MESSAGE = "Hello Log!";

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  private ClassLoader currentClassLoader;

  @Mock
  private ClassLoader additionalClassLoader;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Logger originalLogger;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private LoggerContext containerLoggerContext;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private ContextSelector contextSelector;

  @Mock
  private ArtifactAwareContextSelector artifactAwareContextSelector;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private MessageFactory messageFactory;

  @Mock
  RegionClassLoader regionClassLoader;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  LoggerContext regionClassLoaderLoggerContext;

  private Logger logger;

  @Before
  public void before() {
    currentClassLoader = Thread.currentThread().getContextClassLoader();
    when(containerLoggerContext.getConfiguration().getLoggerConfig(anyString()).getLevel()).thenReturn(Level.INFO);
    logger =
        new DispatchingLogger(originalLogger, currentClassLoader.hashCode(), containerLoggerContext, contextSelector,
                              messageFactory) {

          @Override
          public String getName() {
            return LOGGER_NAME;
          }
        };
  }

  @Test
  public void currentClassLoader() {
    logger.info(MESSAGE);
    verify(originalLogger).info(MESSAGE);
  }

  @Test
  public void anotherClassLoader() {
    withContextClassLoader(additionalClassLoader, () -> {
      logger.info(MESSAGE);
      verify(originalLogger).info(MESSAGE);
    });
  }

  @Test
  public void regionClassLoader() {
    RegionClassLoader regionClassLoader = mock(RegionClassLoader.class);
    withContextClassLoader(regionClassLoader, () -> {
      logger.info(MESSAGE);
      verify(contextSelector).getContext(LOGGER_NAME, regionClassLoader, true);
    });
  }

  @Test
  public void whenRecursiveLoggerContextInstantiationExceptionExpectFallbackUsingContainerClassLoader() {
    // Expected Loggers
    Logger containerLogger = mock(Logger.class);
    Logger regionClassLoaderLogger = mock(Logger.class);
    when(containerLoggerContext.getLogger(anyString(), any(MessageFactory.class))).thenReturn(containerLogger);
    when(regionClassLoaderLoggerContext.getLogger(anyString(), any(MessageFactory.class))).thenReturn(regionClassLoaderLogger);
    when(artifactAwareContextSelector.getContextWithResolvedContextClassLoader(currentClassLoader))
        .thenAnswer(invocation -> containerLoggerContext);
    // Triggers of the expected Loggers
    when(artifactAwareContextSelector.getContextWithResolvedContextClassLoader(regionClassLoader))
        .thenThrow(RecursiveLoggerContextInstantiationException.class)
        .thenAnswer(invocation -> regionClassLoaderLoggerContext);
    // Class under test
    DispatchingLogger dispatchingLogger = new DispatchingLogger(originalLogger, currentClassLoader.hashCode(),
                                                                containerLoggerContext, artifactAwareContextSelector,
                                                                messageFactory) {

      @Override
      public String getName() {
        return LOGGER_NAME;
      }
    };
    // Test and assertions
    withContextClassLoader(regionClassLoader, () -> {
      dispatchingLogger.info("Fallback Test Message");
      dispatchingLogger.info("Test Message");
    });
    verify(containerLogger, times(1)).info("Fallback Test Message");
    verify(regionClassLoaderLogger, times(1)).info("Test Message");
  }

  @Test
  public void whenFallbackToContainerClassLoaderFailsReturnOriginalLogger() {
    // Expected Loggers
    Logger containerLogger = mock(Logger.class);
    Logger regionClassLoaderLogger = mock(Logger.class);
    when(containerLoggerContext.getLogger(anyString(), any(MessageFactory.class))).thenReturn(containerLogger);
    when(regionClassLoaderLoggerContext.getLogger(anyString(), any(MessageFactory.class))).thenReturn(regionClassLoaderLogger);
    when(artifactAwareContextSelector.getContextWithResolvedContextClassLoader(currentClassLoader))
        .thenThrow(RecursiveLoggerContextInstantiationException.class)
        .thenAnswer(invocation -> containerLoggerContext);
    // Triggers of the expected Loggers
    when(artifactAwareContextSelector.getContextWithResolvedContextClassLoader(regionClassLoader))
        .thenThrow(RecursiveLoggerContextInstantiationException.class)
        .thenAnswer(invocation -> regionClassLoaderLoggerContext);
    // Class under test
    DispatchingLogger dispatchingLogger = new DispatchingLogger(originalLogger, currentClassLoader.hashCode(),
                                                                containerLoggerContext, artifactAwareContextSelector,
                                                                messageFactory) {

      @Override
      public String getName() {
        return LOGGER_NAME;
      }
    };
    // Test and assertions
    withContextClassLoader(regionClassLoader, () -> {
      dispatchingLogger.info("Fallback Test Message");
      dispatchingLogger.info("Test Message");
    });
    verify(originalLogger, times(1)).info("Fallback Test Message");
    verify(regionClassLoaderLogger, times(1)).info("Test Message");
  }

}
