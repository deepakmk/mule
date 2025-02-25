/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.launcher.log4j2;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mule.runtime.api.util.MuleSystemProperties.MULE_LOG_CONTEXT_DISPOSE_DELAY_MILLIS;
import static org.mule.runtime.module.launcher.log4j2.LoggerContextReaperThreadFactory.THREAD_NAME;
import static org.mule.runtime.module.launcher.log4j2.MuleLoggerContextFactory.LOG4J_CONFIGURATION_FILE_PROPERTY;
import static org.mule.tck.MuleTestUtils.getRunningThreadByName;

import org.mule.runtime.api.util.Reference;
import org.mule.runtime.deployment.model.api.application.ApplicationDescriptor;
import org.mule.runtime.deployment.model.api.policy.PolicyTemplateDescriptor;
import org.mule.runtime.module.artifact.api.classloader.ArtifactClassLoader;
import org.mule.runtime.module.artifact.api.classloader.ClassLoaderLookupPolicy;
import org.mule.runtime.module.artifact.api.classloader.MuleArtifactClassLoader;
import org.mule.runtime.module.artifact.api.classloader.RegionClassLoader;
import org.mule.runtime.module.artifact.api.classloader.ShutdownListener;
import org.mule.runtime.module.artifact.api.descriptor.DeployableArtifactDescriptor;
import org.mule.runtime.module.reboot.api.MuleContainerBootstrapUtils;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.junit4.rule.SystemProperty;
import org.mule.tck.probe.JUnitProbe;
import org.mule.tck.probe.PollingProber;
import org.mule.tck.probe.Probe;
import org.mule.tck.size.SmallTest;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import org.apache.logging.log4j.core.LifeCycle;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
public class ArtifactAwareContextSelectorTestCase extends AbstractMuleTestCase {

  private static final String POLICY_TEMPLATE_NAME = "policyTemplate";
  private static final String POLICY_TEMPLATE_ARTIFACT_ID = "domain/app/anApp/policy/aPolicy";

  private static final File CONFIG_LOCATION = new File("src/test/resources/log4j2-test-custom.xml");
  private static final int PROBER_TIMEOUT = 5000;
  private static final int PROBER_FREQ = 500;

  @Rule
  public MockitoRule rule = MockitoJUnit.rule().silent();

  @Rule
  public SystemProperty disposeDelay = new SystemProperty(MULE_LOG_CONTEXT_DISPOSE_DELAY_MILLIS, "200");

  @Rule
  public SystemProperty log4jConfigurationFile = new SystemProperty("log4j.configurationFile", null);

  private ArtifactAwareContextSelector selector;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private RegionClassLoader regionClassLoader;

  @Mock
  private DeployableArtifactDescriptor deployableArtifactDescriptor;

  @Before
  public void before() throws Exception {
    selector = new ArtifactAwareContextSelector();

    when(regionClassLoader.getArtifactId()).thenReturn(getClass().getName());
    when(regionClassLoader.findLocalResource("log4j2.xml")).thenReturn(CONFIG_LOCATION.toURI().toURL());
    when(regionClassLoader.getArtifactDescriptor()).thenReturn(deployableArtifactDescriptor);
  }

  @After
  public void after() throws Exception {
    dispose();
  }

  @Test
  public void classLoaderToContext() {
    MuleLoggerContext context = (MuleLoggerContext) selector.getContext(EMPTY, regionClassLoader, true);
    assertThat(context, is(sameInstance(selector.getContext(EMPTY, regionClassLoader, true))));

    regionClassLoader = mock(RegionClassLoader.class, RETURNS_DEEP_STUBS);
    when(regionClassLoader.getArtifactId()).thenReturn(getClass().getName());
    when(regionClassLoader.getArtifactDescriptor()).thenReturn(deployableArtifactDescriptor);
    assertThat(context, not(sameInstance(selector.getContext(EMPTY, regionClassLoader, true))));
  }

  @Test
  public void shutdownListener() {
    MuleLoggerContext context = getContext();

    ArgumentCaptor<ShutdownListener> captor = ArgumentCaptor.forClass(ShutdownListener.class);
    verify(regionClassLoader).addShutdownListener(captor.capture());
    ShutdownListener listener = captor.getValue();
    assertThat(listener, notNullValue());

    assertThat(context, is(selector.getContext(EMPTY, regionClassLoader, true)));
    listener.execute();

    assertStopped(context);
  }

  @Test
  public void dispose() throws Exception {
    MuleLoggerContext context = getContext();

    selector.dispose();
    assertStopped(context);
  }

  @Test
  public void returnsMuleLoggerContext() {
    LoggerContext ctx = selector.getContext("", regionClassLoader, true);
    assertThat(ctx, instanceOf(MuleLoggerContext.class));
    assertConfigurationLocation(ctx);
  }

  @Test
  public void defaultToLog4jSysPropWhenNoConfigFound() {
    System.setProperty(LOG4J_CONFIGURATION_FILE_PROPERTY, "/customLocation/myLogConfig.xml");
    when(regionClassLoader.findLocalResource(anyString())).thenReturn(null);
    File expected = new File("/customLocation/myLogConfig.xml");
    LoggerContext ctx = selector.getContext("", regionClassLoader, true);
    assertThat(ctx.getConfigLocation(), equalTo(expected.toURI()));
  }

  @Test
  public void defaultToConfWhenNoConfigFound() {
    when(regionClassLoader.findLocalResource(anyString())).thenReturn(null);
    File expected = new File(MuleContainerBootstrapUtils.getMuleHome(), "conf");
    expected = new File(expected, "log4j2.xml");
    LoggerContext ctx = selector.getContext("", regionClassLoader, true);
    assertThat(ctx.getConfigLocation(), equalTo(expected.toURI()));
  }

  @Test
  public void usesLoggerContextReaperThread() throws Exception {
    // make sure that selector is disposed therefore the reaper thread is stopped
    dispose();
    assertReaperThreadNotRunning();
    // call again the before process to intialize the selector and reaper thread
    before();

    MuleLoggerContext context = getContext();
    selector.removeContext(context);

    Thread thread = getReaperThread();
    assertThat(thread, is(notNullValue()));
  }

  @Test
  public void returnsMuleLoggerContextForArtifactClassLoaderChild() {
    ClassLoader childClassLoader = new URLClassLoader(new URL[0], regionClassLoader);
    LoggerContext parentCtx = selector.getContext("", regionClassLoader, true);
    LoggerContext childCtx = selector.getContext("", childClassLoader, true);
    assertThat(childCtx, instanceOf(MuleLoggerContext.class));
    assertThat(childCtx, sameInstance(parentCtx));
  }

  @Test
  public void returnsMuleLoggerContextForInternalArtifactClassLoader() {
    ArtifactClassLoader serviceClassLoader =
        new MuleArtifactClassLoader("test", new ApplicationDescriptor("test"), new URL[0], this.getClass().getClassLoader(),
                                    mock(ClassLoaderLookupPolicy.class));
    LoggerContext systemContext = selector.getContext("", this.getClass().getClassLoader(), true);
    LoggerContext serviceCtx = selector.getContext("", serviceClassLoader.getClassLoader(), true);
    assertThat(serviceCtx, instanceOf(MuleLoggerContext.class));
    assertThat(serviceCtx, sameInstance(systemContext));
  }

  @Test
  public void returnsParentContextForPolicyClassloader() throws MalformedURLException {
    ClassLoader childClassLoader = new URLClassLoader(new URL[0], regionClassLoader);
    PolicyTemplateDescriptor policyTemplateDescriptor = new PolicyTemplateDescriptor(POLICY_TEMPLATE_NAME);
    RegionClassLoader policyRegionClassLoader = spy(getPolicyRegionClassLoader(policyTemplateDescriptor));
    MuleArtifactClassLoader policyClassLoader = getPolicyArtifactClassLoader(policyTemplateDescriptor, policyRegionClassLoader);

    LoggerContext appCtx = selector.getContext("", childClassLoader, true);
    LoggerContext policyCtx = selector.getContext("", policyClassLoader, true);

    assertThat(policyCtx, sameInstance(appCtx));
  }

  private void assertReaperThreadNotRunning() {
    PollingProber prober = new PollingProber(PROBER_TIMEOUT, PROBER_FREQ);
    prober.check(new Probe() {

      @Override
      public boolean isSatisfied() {
        return getReaperThread() == null;
      }

      @Override
      public String describeFailure() {
        return "Reaper thread exists from previous test and did not died";
      }
    });
  }

  private Thread getReaperThread() {
    return getRunningThreadByName(THREAD_NAME);
  }

  private MuleLoggerContext getContext() {
    return (MuleLoggerContext) selector.getContext("", regionClassLoader, true);
  }

  private void assertConfigurationLocation(LoggerContext ctx) {
    assertThat(ctx.getConfigLocation(), equalTo(CONFIG_LOCATION.toURI()));
  }

  private RegionClassLoader getPolicyRegionClassLoader(PolicyTemplateDescriptor policyTemplateDescriptor) {
    return new RegionClassLoader(POLICY_TEMPLATE_ARTIFACT_ID, policyTemplateDescriptor, regionClassLoader,
                                 mock(ClassLoaderLookupPolicy.class));
  }

  private MuleArtifactClassLoader getPolicyArtifactClassLoader(PolicyTemplateDescriptor policyTemplateDescriptor,
                                                               RegionClassLoader policyRegionClassLoader) {
    return new MuleArtifactClassLoader(POLICY_TEMPLATE_ARTIFACT_ID, policyTemplateDescriptor, new URL[0], policyRegionClassLoader,
                                       mock(ClassLoaderLookupPolicy.class));
  }

  private void assertStopped(final MuleLoggerContext context) {
    final Reference<Boolean> contextWasAccessibleDuringShutdown = new Reference<>(true);
    PollingProber pollingProber = new PollingProber(1000, 10);
    pollingProber.check(new JUnitProbe() {

      @Override
      protected boolean test() throws Exception {
        if (context.getState().equals(LifeCycle.State.STOPPED)) {
          assertThat(context, not(getContext()));
          return true;
        } else {
          LoggerContext currentContext = getContext();
          if (currentContext != null && currentContext != context) {
            contextWasAccessibleDuringShutdown.set(false);
          }
          return false;
        }
      }

      @Override
      public String describeFailure() {
        return "context was not stopped";
      }
    });

    assertThat(contextWasAccessibleDuringShutdown.get(), is(true));
  }
}
