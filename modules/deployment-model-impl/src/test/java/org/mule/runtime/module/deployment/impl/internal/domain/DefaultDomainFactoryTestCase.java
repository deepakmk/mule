/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.impl.internal.domain;

import static org.mule.runtime.core.internal.config.RuntimeLockFactoryUtil.getRuntimeLockFactory;
import static org.mule.runtime.deployment.model.api.domain.DomainDescriptor.DEFAULT_DOMAIN_NAME;
import static org.mule.runtime.module.artifact.activation.internal.deployable.AbstractDeployableProjectModelBuilder.defaultDeployableProjectModelBuilder;
import static org.mule.runtime.module.artifact.activation.internal.deployable.MuleDeployableProjectModelBuilder.isHeavyPackage;
import static org.mule.test.allure.AllureConstants.DeployableCreationFeature.DOMAIN_CREATION;
import static org.mule.test.allure.AllureConstants.DeploymentTypeFeature.DeploymentTypeStory.HEAVYWEIGHT;

import static java.util.Optional.empty;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

import org.mule.runtime.api.memory.management.MemoryManagementService;
import org.mule.runtime.api.service.ServiceRepository;
import org.mule.runtime.deployment.model.api.artifact.ArtifactConfigurationProcessor;
import org.mule.runtime.deployment.model.api.builder.DomainClassLoaderBuilder;
import org.mule.runtime.deployment.model.api.builder.DomainClassLoaderBuilderFactory;
import org.mule.runtime.deployment.model.api.domain.Domain;
import org.mule.runtime.deployment.model.api.domain.DomainDescriptor;
import org.mule.runtime.deployment.model.internal.domain.AbstractDomainTestCase;
import org.mule.runtime.module.artifact.activation.api.deployable.DeployableProjectModel;
import org.mule.runtime.module.artifact.activation.api.descriptor.DeployableArtifactDescriptorCreator;
import org.mule.runtime.module.artifact.activation.api.descriptor.DeployableArtifactDescriptorFactory;
import org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelLoaderRepository;
import org.mule.runtime.module.artifact.activation.internal.classloader.MuleApplicationClassLoader;
import org.mule.runtime.module.artifact.activation.internal.deployable.AbstractDeployableProjectModelBuilder;
import org.mule.runtime.module.artifact.activation.internal.deployable.MuleDeployableProjectModelBuilder;
import org.mule.runtime.module.license.api.LicenseValidator;

import java.io.File;
import java.io.IOException;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

@Feature(DOMAIN_CREATION)
@Story(HEAVYWEIGHT)
@RunWith(PowerMockRunner.class)
@PrepareForTest({MuleDeployableProjectModelBuilder.class, DeployableProjectModel.class, DefaultDomainFactory.class,
    AbstractDeployableProjectModelBuilder.class})
@PowerMockIgnore({"javax.management.*", "javax.script.*"})
@PowerMockRunnerDelegate(JUnit4.class)
public class DefaultDomainFactoryTestCase extends AbstractDomainTestCase {

  private final ServiceRepository serviceRepository = mock(ServiceRepository.class);
  private final DeployableArtifactDescriptorFactory deployableArtifactDescriptorFactory =
      mock(DeployableArtifactDescriptorFactory.class);
  private final DomainClassLoaderBuilderFactory domainClassLoaderBuilderFactory = mock(DomainClassLoaderBuilderFactory.class);
  private final ExtensionModelLoaderRepository extensionModelLoaderRepository = mock(ExtensionModelLoaderRepository.class);
  private final LicenseValidator licenseValidator = mock(LicenseValidator.class);
  private final DefaultDomainFactory domainFactory = new DefaultDomainFactory(mock(DomainDescriptorFactory.class),
                                                                              deployableArtifactDescriptorFactory,
                                                                              new DefaultDomainManager(),
                                                                              null,
                                                                              serviceRepository,
                                                                              domainClassLoaderBuilderFactory,
                                                                              extensionModelLoaderRepository,
                                                                              licenseValidator,
                                                                              getRuntimeLockFactory(),
                                                                              mock(MemoryManagementService.class),
                                                                              mock(ArtifactConfigurationProcessor.class));

  public DefaultDomainFactoryTestCase() throws IOException {}

  @Before
  public void setUp() throws Exception {
    mockStatic(AbstractDeployableProjectModelBuilder.class);
    when(isHeavyPackage(any())).thenReturn(true);
    when(defaultDeployableProjectModelBuilder(any(), any(), anyBoolean())).thenCallRealMethod();
    DeployableProjectModel deployableProjectModelMock = PowerMockito.mock(DeployableProjectModel.class);
    doNothing().when(deployableProjectModelMock).validate();
    MuleDeployableProjectModelBuilder muleDeployableProjectModelBuilderMock =
        PowerMockito.mock(MuleDeployableProjectModelBuilder.class);
    when(muleDeployableProjectModelBuilderMock.build()).thenReturn(deployableProjectModelMock);
    whenNew(MuleDeployableProjectModelBuilder.class).withAnyArguments().thenReturn(muleDeployableProjectModelBuilderMock);
  }

  @Test
  public void createDefaultDomain() throws IOException {
    final MuleApplicationClassLoader domainArtifactClassLoader = mock(MuleApplicationClassLoader.class);
    when(domainArtifactClassLoader.getArtifactId()).thenReturn(DEFAULT_DOMAIN_NAME);

    DomainClassLoaderBuilder domainClassLoaderBuilderMock = mock(DomainClassLoaderBuilder.class);
    when(domainClassLoaderBuilderMock.setArtifactDescriptor(any())).thenReturn(domainClassLoaderBuilderMock);
    when(domainClassLoaderBuilderMock.build()).thenReturn(domainArtifactClassLoader);
    when(domainClassLoaderBuilderFactory.createArtifactClassLoaderBuilder()).thenReturn(domainClassLoaderBuilderMock);

    Domain domain = domainFactory.createArtifact(new File(DEFAULT_DOMAIN_NAME), empty());
    assertThat(domain.getArtifactName(), is(DEFAULT_DOMAIN_NAME));
    assertThat(domain.getDescriptor(), instanceOf(EmptyDomainDescriptor.class));
    assertThat(domain.getArtifactClassLoader(), is(domainArtifactClassLoader));
  }

  @Test
  public void createCustomDomain() throws IOException {
    String domainName = "custom-domain";

    final DomainDescriptor descriptor = new DomainDescriptor(domainName);
    when(deployableArtifactDescriptorFactory.createDomainDescriptor(any(), any(), any(DeployableArtifactDescriptorCreator.class)))
        .thenReturn(descriptor);

    final MuleApplicationClassLoader domainArtifactClassLoader = mock(MuleApplicationClassLoader.class);
    when(domainArtifactClassLoader.getArtifactId()).thenReturn(domainName);

    DomainClassLoaderBuilder domainClassLoaderBuilderMock = mock(DomainClassLoaderBuilder.class);
    when(domainClassLoaderBuilderMock.setArtifactDescriptor(any()))
        .thenReturn(domainClassLoaderBuilderMock);
    when(domainClassLoaderBuilderMock.build()).thenReturn(domainArtifactClassLoader);
    when(domainClassLoaderBuilderFactory.createArtifactClassLoaderBuilder()).thenReturn(domainClassLoaderBuilderMock);

    Domain domain = domainFactory.createArtifact(new File(domainName), empty());

    assertThat(domain.getArtifactName(), is(domainName));
    assertThat(domain.getDescriptor(), is(descriptor));
    assertThat(domain.getArtifactClassLoader(), is(domainArtifactClassLoader));
  }

}
