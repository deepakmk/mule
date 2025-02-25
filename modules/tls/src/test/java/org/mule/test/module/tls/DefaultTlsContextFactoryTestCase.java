/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.tls;

import static java.util.Collections.emptyMap;
import static javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm;
import static org.apache.commons.lang3.SystemUtils.IS_JAVA_1_8;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.mule.functional.junit4.matchers.ThrowableCauseMatcher.hasCause;
import static org.mule.functional.junit4.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mule.runtime.api.config.MuleRuntimeFeature.HONOUR_INSECURE_TLS_CONFIGURATION;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.privileged.security.tls.TlsConfiguration.DEFAULT_SECURITY_MODEL;
import static org.mule.runtime.core.privileged.security.tls.TlsConfiguration.PROPERTIES_FILE_PATTERN;

import org.mule.runtime.api.config.FeatureFlaggingService;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.tls.TlsContextFactory;
import org.mule.runtime.core.api.util.ClassUtils;
import org.mule.runtime.core.api.util.StringUtils;
import org.mule.runtime.module.tls.internal.DefaultTlsContextFactory;
import org.mule.tck.junit4.AbstractMuleTestCase;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultTlsContextFactoryTestCase extends AbstractMuleTestCase {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @BeforeClass
  public static void createTlsPropertiesFile() throws Exception {

    PrintWriter writer = new PrintWriter(getTlsPropertiesFile(), "UTF-8");
    writer.println("enabledCipherSuites=" + getFileEnabledCipherSuites());
    writer.println("enabledProtocols=" + getFileEnabledProtocols());
    writer.close();
  }

  @AfterClass
  public static void removeTlsPropertiesFile() {
    getTlsPropertiesFile().delete();
  }

  private static File getTlsPropertiesFile() {
    String path = ClassUtils.getClassPathRoot(DefaultTlsContextFactoryTestCase.class).getPath();
    return new File(path, String.format(PROPERTIES_FILE_PATTERN, DEFAULT_SECURITY_MODEL));
  }

  public static String getFileEnabledProtocols() {
    return "TLSv1.1, TLSv1.2";
  }

  public static String getFileEnabledCipherSuites() {
    return "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256, TLS_DHE_DSS_WITH_AES_128_CBC_SHA";
  }

  @Test
  public void failIfKeyStoreHasNoKey() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.setKeyStorePath("trustStore");
    tlsContextFactory.setKeyStorePassword("mulepassword");
    tlsContextFactory.setKeyPassword("mulepassword");
    expectedException.expectCause(hasCause(isA(IllegalArgumentException.class)));
    expectedException.expectCause(hasCause(hasMessage("No key entries found.")));
    tlsContextFactory.initialise();
  }

  @Test
  public void failIfKeyStoreAliasIsNotAKey() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.setKeyStorePath("serverKeystore");
    tlsContextFactory.setKeyAlias("muleclient");
    tlsContextFactory.setKeyStorePassword("mulepassword");
    tlsContextFactory.setKeyPassword("mulepassword");
    expectedException.expectCause(hasCause(isA(IllegalArgumentException.class)));
    expectedException.expectCause(hasCause(hasMessage("Keystore entry for alias 'muleclient' is not a key.")));
    tlsContextFactory.initialise();
  }

  @Test
  public void failIfTrustStoreIsNonexistent() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    expectedException.expect(IOException.class);
    expectedException.expectMessage(containsString("Resource non-existent-trust-store could not be found"));
    tlsContextFactory.setTrustStorePath("non-existent-trust-store");
  }

  @Test
  public void insecureTrustStoreShouldNotBeConfiguredIfFFIsEnabled() throws IOException, InitialisationException {
    assertTrue(getFeatureFlaggingService().isEnabled(HONOUR_INSECURE_TLS_CONFIGURATION));
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap(), getFeatureFlaggingService());
    tlsContextFactory.setTrustStorePath("trustStore");
    tlsContextFactory.setTrustStoreInsecure(true);
    assertFalse(tlsContextFactory.isTrustStoreConfigured());
  }

  @Test
  public void insecureTrustStoreShouldBeConfiguredIfFFIsDisabled() throws IOException, InitialisationException {
    assertFalse(getFeatureFlaggingServiceWithFFDisabled().isEnabled(HONOUR_INSECURE_TLS_CONFIGURATION));
    DefaultTlsContextFactory tlsContextFactory =
        new DefaultTlsContextFactory(emptyMap(), getFeatureFlaggingServiceWithFFDisabled());
    tlsContextFactory.setTrustStorePath("trustStore");
    tlsContextFactory.setTrustStoreInsecure(true);
    assertTrue(tlsContextFactory.isTrustStoreConfigured());
  }

  @Test
  public void useConfigFileIfDefaultProtocolsAndCipherSuites() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.setEnabledCipherSuites("DEFAULT");
    tlsContextFactory.setEnabledProtocols("default");
    tlsContextFactory.initialise();

    assertThat(tlsContextFactory.getEnabledCipherSuites(), is(StringUtils.splitAndTrim(getFileEnabledCipherSuites(), ",")));
    assertThat(tlsContextFactory.getEnabledProtocols(), is(StringUtils.splitAndTrim(getFileEnabledProtocols(), ",")));
  }

  @Test
  public void trustStoreAlgorithmInTlsContextIsDefaultTrustManagerAlgorithm() {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    assertThat(tlsContextFactory.getTrustManagerAlgorithm(), equalTo(getDefaultAlgorithm()));
  }

  @Test
  public void overrideConfigFile() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.setEnabledCipherSuites("TLS_DHE_DSS_WITH_AES_128_CBC_SHA");
    tlsContextFactory.setEnabledProtocols("TLSv1.1");
    tlsContextFactory.initialise();

    String[] enabledCipherSuites = tlsContextFactory.getEnabledCipherSuites();
    assertThat(enabledCipherSuites.length, is(1));
    assertThat(enabledCipherSuites, is(arrayContaining("TLS_DHE_DSS_WITH_AES_128_CBC_SHA")));

    String[] enabledProtocols = tlsContextFactory.getEnabledProtocols();
    assertThat(enabledProtocols.length, is(1));
    assertThat(enabledProtocols, is(arrayContaining("TLSv1.1")));
  }

  @Test
  public void failIfProtocolsDoNotMatchConfigFile() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.setEnabledProtocols("TLSv1,SSLv3");
    expectedException.expect(InitialisationException.class);
    expectedException.expectMessage(containsString("protocols are invalid"));
    tlsContextFactory.initialise();
  }

  @Test
  public void failIfCipherSuitesDoNotMatchConfigFile() throws Exception {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.setEnabledCipherSuites("SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA");
    expectedException.expect(InitialisationException.class);
    expectedException.expectMessage(containsString("cipher suites are invalid"));
    tlsContextFactory.initialise();
  }

  @Test
  public void cannotMutateEnabledProtocols() throws InitialisationException {
    TlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    initialiseIfNeeded(tlsContextFactory);
    tlsContextFactory.getEnabledProtocols()[0] = "TLSv1";
    assertThat(tlsContextFactory.getEnabledProtocols(), arrayWithSize(2));
    assertThat(tlsContextFactory.getEnabledProtocols(), arrayContaining("TLSv1.1", "TLSv1.2"));
  }

  @Test
  public void cannotMutateEnabledCipherSuites() throws InitialisationException {
    TlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    initialiseIfNeeded(tlsContextFactory);
    tlsContextFactory.getEnabledCipherSuites()[0] = "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256";
    assertThat(tlsContextFactory.getEnabledCipherSuites(), arrayWithSize(2));
    assertThat(tlsContextFactory.getEnabledCipherSuites(), arrayContaining("TLS_DHE_DSS_WITH_AES_128_CBC_SHA256",
                                                                           "TLS_DHE_DSS_WITH_AES_128_CBC_SHA"));
  }

  @Test
  public void defaultIncludesTls12Ciphers() throws Exception {
    assumeThat(IS_JAVA_1_8, is(true));

    defaultIncludesDEfaultTlsVersionCiphers("TLSv1.2");
  }

  @Test
  public void defaultIncludesTls13Ciphers() throws Exception {
    // For versions greater than 8, the default is TLS 1.3
    assumeThat(IS_JAVA_1_8, is(false));

    defaultIncludesDEfaultTlsVersionCiphers("TLSv1.3");
  }

  private FeatureFlaggingService getFeatureFlaggingServiceWithFFDisabled() {
    return feature -> !feature.equals(HONOUR_INSECURE_TLS_CONFIGURATION);
  }

  private void defaultIncludesDEfaultTlsVersionCiphers(String sslVersion)
      throws InitialisationException, KeyManagementException, NoSuchAlgorithmException {
    DefaultTlsContextFactory tlsContextFactory = new DefaultTlsContextFactory(emptyMap());
    tlsContextFactory.initialise();
    SSLSocketFactory defaultFactory = tlsContextFactory.createSslContext().getSocketFactory();
    SSLContext tlsContext = SSLContext.getInstance(sslVersion);
    tlsContext.init(null, null, null);
    SSLSocketFactory tlsFactory = tlsContext.getSocketFactory();

    assertThat(defaultFactory.getDefaultCipherSuites(), arrayContainingInAnyOrder(tlsFactory.getDefaultCipherSuites()));
  }

}
