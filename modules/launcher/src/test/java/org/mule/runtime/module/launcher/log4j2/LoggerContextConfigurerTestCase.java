/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.launcher.log4j2;

import static java.util.Optional.empty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mule.runtime.api.util.MuleSystemProperties.MULE_FORCE_CONSOLE_LOG;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_MUTE_APP_LOGS_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.module.launcher.log4j2.LoggerContextConfigurer.FORCED_CONSOLE_APPENDER_NAME;
import static org.mule.runtime.module.launcher.log4j2.LoggerContextConfigurer.PER_APP_FILE_APPENDER_NAME;

import org.mule.runtime.core.api.util.ClassUtils;
import org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor;
import org.mule.runtime.module.artifact.api.descriptor.DeployableArtifactDescriptor;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.ConfigurationFileWatcher;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Reconfigurable;
import org.apache.logging.log4j.core.util.WatchManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
public class LoggerContextConfigurerTestCase extends AbstractMuleTestCase {

  private static final String CURRENT_DIRECTORY = ".";
  private static final String SHUTDOWN_HOOK_PROPERTY = "isShutdownHookEnabled";
  private static final int MONITOR_INTERVAL = 60000;
  private static final String CONVERTER_COMPONENT = "Converter";
  private static final String FILE_PATTERN_PROPERTY = "filePattern";
  private static final String FILE_PATTERN_TEMPLATE_DATE_SECTION = "%d{yyyy-MM-dd}";

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  private LoggerContextConfigurer contextConfigurer;

  @Mock(answer = RETURNS_DEEP_STUBS)
  private MuleLoggerContext context;

  @Mock(answer = RETURNS_DEEP_STUBS, extraInterfaces = {Reconfigurable.class})
  private DefaultConfiguration configuration;

  private Object converter;

  @Before
  public void before() {
    contextConfigurer = new LoggerContextConfigurer();
    when(context.isStandalone()).thenReturn(true);
    when(context.getConfiguration()).thenReturn(configuration);

    converter = null;

    doAnswer(invocation -> {
      converter = invocation.getArguments()[1];
      return null;
    }).when(configuration).addComponent(eq("Converter"), anyObject());

    when(configuration.getComponent(CONVERTER_COMPONENT)).thenAnswer(invocation -> converter);
  }

  @Test
  public void disableShutdownHook() throws Exception {
    contextConfigurer.configure(context);
    assertThat((boolean) ClassUtils.getFieldValue(context.getConfiguration(), SHUTDOWN_HOOK_PROPERTY, true), is(false));
  }

  @Test
  public void configurationMonitor() throws Exception {
    WatchManager watchManager = mock(WatchManager.class);
    when(configuration.getWatchManager()).thenReturn(watchManager);

    when(context.getConfigFile()).thenReturn(new File(CURRENT_DIRECTORY).toURI());
    contextConfigurer.configure(context);
    ArgumentCaptor<ConfigurationFileWatcher> captor = ArgumentCaptor.forClass(ConfigurationFileWatcher.class);
    verify(watchManager).watchFile(any(File.class), captor.capture());

    assertThat(captor.getValue(), instanceOf(ConfigurationFileWatcher.class));
    verify(watchManager).setIntervalSeconds(eq((int) MILLISECONDS.toSeconds(MONITOR_INTERVAL)));
  }

  @Test
  public void forceConsoleLog() {
    withForceConsoleLog(() -> {
      contextConfigurer.update(context);
      ArgumentCaptor<ConsoleAppender> appenderCaptor = ArgumentCaptor.forClass(ConsoleAppender.class);
      verify(context.getConfiguration()).addAppender(appenderCaptor.capture());

      Appender forcedConsoleAppender = appenderCaptor.getValue();

      assertThat(forcedConsoleAppender, notNullValue());
      assertThat(forcedConsoleAppender.getName(), equalTo(FORCED_CONSOLE_APPENDER_NAME));
      assertThat(forcedConsoleAppender.isStarted(), is(true));

      LoggerConfig rootLogger = ((AbstractConfiguration) context.getConfiguration()).getRootLogger();
      verify(rootLogger).addAppender(forcedConsoleAppender, Level.ALL, null);
    });
  }

  @Test
  public void replaceColonWithDash() {
    when(context.getArtifactName()).thenReturn("my:app");
    when(context.isArtifactClassloader()).thenReturn(true);
    contextConfigurer.update(context);
    ArgumentCaptor<RollingFileAppender> appenderCaptor = ArgumentCaptor.forClass(RollingFileAppender.class);
    verify(context.getConfiguration(), atLeastOnce()).addAppender(appenderCaptor.capture());
    assertThat(appenderCaptor.getValue().getFileName().contains(":"), is(false));
  }

  @Test
  public void perAppDefaultAppender() throws Exception {
    when(context.isArtifactClassloader()).thenReturn(true);
    when(context.getArtifactDescriptor().getDeploymentProperties()).thenReturn(empty());
    contextConfigurer.update(context);
    ArgumentCaptor<RollingFileAppender> appenderCaptor = ArgumentCaptor.forClass(RollingFileAppender.class);
    verify(context.getConfiguration()).addAppender(appenderCaptor.capture());

    Appender perAppAppender = appenderCaptor.getValue();

    assertThat(perAppAppender, notNullValue());
    assertThat(perAppAppender.getName(), equalTo(PER_APP_FILE_APPENDER_NAME));
    assertThat(perAppAppender.isStarted(), is(true));

    String filePattern = ClassUtils.getFieldValue(perAppAppender, FILE_PATTERN_PROPERTY, true);
    String filePatternTemplate = filePattern.substring(filePattern.lastIndexOf('/') + 1);
    String filePatternTemplateDateSuffix = filePatternTemplate.substring(filePatternTemplate.lastIndexOf('.') + 1);
    assertThat(filePatternTemplateDateSuffix, equalTo(FILE_PATTERN_TEMPLATE_DATE_SECTION));

    LoggerConfig rootLogger = context.getConfiguration().getRootLogger();
    verify(rootLogger).addAppender(perAppAppender, Level.ALL, null);
  }

  @Test
  public void noAppendersForMutedApplication() throws Exception {
    when(context.isArtifactClassloader()).thenReturn(true);
    DeployableArtifactDescriptor descriptor = mock(DeployableArtifactDescriptor.class);

    Properties properties = new Properties();
    properties.setProperty(MULE_MUTE_APP_LOGS_DEPLOYMENT_PROPERTY, "true");
    when(descriptor.getDeploymentProperties()).thenReturn(Optional.of(properties));
    when(context.getArtifactDescriptor()).thenReturn(descriptor);

    contextConfigurer.update(context);

    verify(context.getConfiguration(), never()).addAppender(any(Appender.class));
  }

  @Test
  public void forceConsoleLogWithAppenderAlreadyPresent() {
    withForceConsoleLog(() -> {
      LoggerConfig rootLogger = ((AbstractConfiguration) context.getConfiguration()).getRootLogger();
      Collection<Appender> appenders = new ArrayList<>();
      appenders.add(ConsoleAppender.createAppender(mock(Layout.class), null, null, "Console", null, null));
      when(rootLogger.getAppenders().values()).thenReturn(appenders);

      contextConfigurer.configure(context);
      verify(context.getConfiguration(), never()).addAppender(any(ConsoleAppender.class));
      verify(rootLogger, never()).addAppender(any(ConsoleAppender.class), same(Level.INFO), any(Filter.class));
    });
  }

  private void withForceConsoleLog(Runnable assertion) {
    System.setProperty(MULE_FORCE_CONSOLE_LOG, "");
    try {
      assertion.run();
    } finally {
      System.clearProperty(MULE_FORCE_CONSOLE_LOG);
    }
  }
}
