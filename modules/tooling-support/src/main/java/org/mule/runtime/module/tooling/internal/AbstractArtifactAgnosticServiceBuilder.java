/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal;

import static org.mule.runtime.api.deployment.meta.Product.MULE;
import static org.mule.runtime.api.util.Preconditions.checkState;
import static org.mule.runtime.container.api.MuleFoldersUtil.getExecutionFolder;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor.META_INF;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor.MULE_ARTIFACT;
import static org.mule.runtime.module.artifact.api.descriptor.BundleDescriptor.MULE_PLUGIN_CLASSIFIER;
import static org.mule.runtime.module.deployment.impl.internal.maven.MavenUtils.addSharedLibraryDependency;
import static org.mule.runtime.module.deployment.impl.internal.maven.MavenUtils.createDeployablePomFile;
import static org.mule.runtime.module.deployment.impl.internal.maven.MavenUtils.updateArtifactPom;

import static java.nio.file.Files.createDirectories;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static java.util.Optional.of;
import static java.util.stream.Collectors.toList;

import static com.google.common.collect.Lists.newArrayList;

import org.mule.maven.client.api.MavenClientProvider;
import org.mule.runtime.api.deployment.meta.MuleApplicationModel;
import org.mule.runtime.api.deployment.meta.MuleArtifactLoaderDescriptor;
import org.mule.runtime.api.deployment.persistence.MuleApplicationModelJsonSerializer;
import org.mule.runtime.api.meta.MuleVersion;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.core.api.config.bootstrap.ArtifactType;
import org.mule.runtime.core.api.util.UUID;
import org.mule.runtime.globalconfig.api.GlobalConfigLoader;
import org.mule.runtime.module.artifact.api.descriptor.ApplicationDescriptor;
import org.mule.runtime.module.artifact.api.descriptor.BundleDescriptor;
import org.mule.runtime.module.artifact.api.descriptor.ClassLoaderConfiguration;
import org.mule.runtime.module.deployment.impl.internal.application.DefaultApplicationFactory;
import org.mule.runtime.module.deployment.impl.internal.application.DeployableMavenClassLoaderConfigurationLoader;
import org.mule.runtime.module.tooling.api.ArtifactAgnosticServiceBuilder;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import org.apache.maven.model.Model;

public abstract class AbstractArtifactAgnosticServiceBuilder<T extends ArtifactAgnosticServiceBuilder, S>
    implements ArtifactAgnosticServiceBuilder<T, S> {

  private static final String TMP_APP_ARTIFACT_ID = "temp-artifact-id";
  private static final String TMP_APP_GROUP_ID = "temp-group-id";
  private static final String TMP_APP_VERSION = "temp-version";
  private static final String TMP_APP_MODEL_VERSION = "4.0.0";

  private final DefaultApplicationFactory defaultApplicationFactory;

  private ArtifactDeclaration artifactDeclaration;
  private Model model;
  private Map<String, String> artifactProperties = emptyMap();

  protected AbstractArtifactAgnosticServiceBuilder(DefaultApplicationFactory defaultApplicationFactory) {
    this.defaultApplicationFactory = defaultApplicationFactory;
    createTempMavenModel();
  }

  @Override
  public T setArtifactProperties(Map<String, String> artifactProperties) {
    checkState(artifactProperties != null, "artifactProperties cannot be null");
    this.artifactProperties = artifactProperties;
    return getThis();
  }

  @Override
  public T setArtifactDeclaration(ArtifactDeclaration artifactDeclaration) {
    checkState(artifactDeclaration != null, "artifactDeclaration cannot be null");
    this.artifactDeclaration = artifactDeclaration;
    return getThis();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T addDependency(String groupId, String artifactId, String artifactVersion,
                         String classifier, String type) {
    org.apache.maven.model.Dependency dependency = new org.apache.maven.model.Dependency();
    dependency.setGroupId(groupId);
    dependency.setArtifactId(artifactId);
    dependency.setVersion(artifactVersion);
    dependency.setType(type);
    dependency.setClassifier(classifier);

    addMavenModelDependency(dependency);
    return getThis();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T addDependency(Dependency dependency) {
    org.apache.maven.model.Dependency mavenModelDependency = new org.apache.maven.model.Dependency();
    mavenModelDependency.setGroupId(dependency.getGroupId());
    mavenModelDependency.setArtifactId(dependency.getArtifactId());
    mavenModelDependency.setVersion(dependency.getVersion());
    mavenModelDependency.setType(dependency.getType());
    mavenModelDependency.setClassifier(dependency.getClassifier());
    mavenModelDependency.setOptional(dependency.getOptional());
    mavenModelDependency.setScope(dependency.getScope());
    mavenModelDependency.setSystemPath(dependency.getSystemPath());
    mavenModelDependency.setExclusions(dependency.getExclusions().stream().map(exclusion -> {
      org.apache.maven.model.Exclusion mavenModelExclusion = new org.apache.maven.model.Exclusion();
      mavenModelExclusion.setGroupId(exclusion.getGroupId());
      mavenModelExclusion.setArtifactId(exclusion.getArtifactId());
      return mavenModelExclusion;
    }).collect(toList()));

    addMavenModelDependency(mavenModelDependency);
    return getThis();
  }

  private void addMavenModelDependency(org.apache.maven.model.Dependency dependency) {
    if (!MULE_PLUGIN_CLASSIFIER.equals(dependency.getClassifier())) {
      addSharedLibraryDependency(model, dependency);
    }
    model.getDependencies().add(dependency);
  }

  @Override
  public S build() {
    checkState(artifactDeclaration != null, "artifact configuration cannot be null");
    return createService(() -> {
      String applicationName = UUID.getUUID() + "-artifact-temp-app";
      File applicationFolder = new File(getExecutionFolder(), applicationName);
      Properties deploymentProperties = new Properties();
      deploymentProperties.putAll(forcedDeploymentProperties());
      Set<String> configs = singleton("empty-app.xml");

      createDeployablePomFile(applicationFolder, model);
      updateArtifactPom(applicationFolder, model);

      MavenClientProvider mavenClientProvider =
          MavenClientProvider.discoverProvider(AbstractArtifactAgnosticServiceBuilder.class.getClassLoader());
      ClassLoaderConfiguration classLoaderConfiguration =
          new DeployableMavenClassLoaderConfigurationLoader(of(mavenClientProvider
              .createMavenClient(GlobalConfigLoader.getMavenConfig())))
                  .load(applicationFolder, singletonMap(BundleDescriptor.class.getName(),
                                                        createTempBundleDescriptor()),
                        ArtifactType.APP);

      File destinationFolder =
          applicationFolder.toPath().resolve(META_INF).resolve(MULE_ARTIFACT).toFile();
      createDirectories(destinationFolder.toPath());

      MuleVersion muleVersion = new MuleVersion("4.4.0");
      String artifactJson =
          new MuleApplicationModelJsonSerializer().serialize(serializeModel(applicationName, classLoaderConfiguration,
                                                                            configs,
                                                                            muleVersion.toCompleteNumericVersion()));
      try (FileWriter fileWriter = new FileWriter(new File(destinationFolder, "mule-artifact.json"))) {
        fileWriter.write(artifactJson);
      }

      ApplicationDescriptor artifactDescriptor =
          defaultApplicationFactory.createArtifactDescriptor(applicationFolder, of(deploymentProperties));
      artifactDescriptor.setMinMuleVersion(muleVersion);
      artifactDescriptor.setArtifactDeclaration(artifactDeclaration);
      return defaultApplicationFactory.createArtifact(artifactDescriptor);
    });
  }

  private MuleApplicationModel serializeModel(String appName,
                                              ClassLoaderConfiguration classLoaderConfiguration,
                                              Set<String> configs, String muleVersion) {
    Map<String, Object> attributes = ImmutableMap.of("exportedResources",
                                                     newArrayList(classLoaderConfiguration.getExportedResources()),
                                                     "exportedPackages",
                                                     newArrayList(classLoaderConfiguration.getExportedPackages()));
    MuleArtifactLoaderDescriptor muleArtifactLoaderDescriptor = new MuleArtifactLoaderDescriptor("mule", attributes);
    MuleApplicationModel.MuleApplicationModelBuilder builder = new MuleApplicationModel.MuleApplicationModelBuilder();
    builder.setName(appName)
        .setMinMuleVersion(muleVersion)
        .setRequiredProduct(MULE)
        .withBundleDescriptorLoader(new MuleArtifactLoaderDescriptor("mule", emptyMap()))
        .withClassLoaderModelDescriptorLoader(muleArtifactLoaderDescriptor)
        .setConfigs(configs);
    return builder.build();
  }



  protected Map<String, String> forcedDeploymentProperties() {
    return emptyMap();
  }

  protected abstract S createService(ApplicationSupplier applicationSupplier);

  private void createTempMavenModel() {
    model = new Model();
    model.setArtifactId(TMP_APP_ARTIFACT_ID);
    model.setGroupId(TMP_APP_GROUP_ID);
    model.setVersion("4.4.0");
    model.setDependencies(new ArrayList<>());
    model.setModelVersion(TMP_APP_MODEL_VERSION);
  }

  private BundleDescriptor createTempBundleDescriptor() {
    return new BundleDescriptor.Builder().setArtifactId(TMP_APP_ARTIFACT_ID).setGroupId(TMP_APP_GROUP_ID)
        .setVersion(TMP_APP_VERSION).setClassifier("mule-application").build();
  }

  private T getThis() {
    return (T) this;
  }

}
