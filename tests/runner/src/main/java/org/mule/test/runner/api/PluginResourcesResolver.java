/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.test.runner.api;

import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static org.mule.runtime.deployment.model.api.artifact.ArtifactDescriptorConstants.EXPORTED_PACKAGES;
import static org.mule.runtime.deployment.model.api.artifact.ArtifactDescriptorConstants.EXPORTED_RESOURCES;
import static org.mule.runtime.deployment.model.api.artifact.ArtifactDescriptorConstants.PRIVILEGED_ARTIFACTS_IDS;
import static org.mule.runtime.deployment.model.api.artifact.ArtifactDescriptorConstants.PRIVILEGED_EXPORTED_PACKAGES;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor.MULE_ARTIFACT_JSON_DESCRIPTOR;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactPluginDescriptor.MULE_ARTIFACT_PATH_INSIDE_JAR;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactPluginDescriptor.MULE_AUTO_GENERATED_ARTIFACT_PATH_INSIDE_JAR;

import org.mule.runtime.api.deployment.meta.MulePluginModel;
import org.mule.runtime.api.deployment.persistence.MulePluginModelJsonSerializer;
import org.mule.test.runner.utils.TroubleshootingUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@link PluginUrlClassification} resources, exported packages and resources.
 *
 * @since 4.0
 */
public class PluginResourcesResolver {

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  /**
   * Resolves for the given {@link PluginUrlClassification} the resources exported.
   *
   * @param pluginUrlClassification {@link PluginUrlClassification} to be resolved
   * @return {@link PluginUrlClassification} with the resources resolved
   */
  public PluginUrlClassification resolvePluginResourcesFor(PluginUrlClassification pluginUrlClassification) {
    final Set<String> exportPackages = newHashSet();
    final Set<String> exportResources = newHashSet();
    final Set<String> privilegedExportedPackages = newHashSet();
    final Set<String> privilegedArtifacts = newHashSet();

    try (URLClassLoader classLoader = new URLClassLoader(pluginUrlClassification.getUrls().toArray(new URL[0]), null)) {
      final String MULE_ARTIFACT_JSON_PATH_INSIDE_JAR = MULE_ARTIFACT_PATH_INSIDE_JAR + "/" + MULE_ARTIFACT_JSON_DESCRIPTOR;
      String pluginDescriptorLocation = MULE_ARTIFACT_JSON_PATH_INSIDE_JAR;

      logger.debug("Loading plugin '{}' descriptor", pluginUrlClassification.getName());
      URL pluginJsonUrl = classLoader.getResource(MULE_ARTIFACT_JSON_PATH_INSIDE_JAR);
      if (pluginJsonUrl == null) {
        pluginJsonUrl = classLoader.getResource(MULE_AUTO_GENERATED_ARTIFACT_PATH_INSIDE_JAR);
        if (pluginJsonUrl == null) {
          throw new IllegalStateException(MULE_ARTIFACT_JSON_DESCRIPTOR + " couldn't be found for plugin: " +
              pluginUrlClassification.getName());
        }
        pluginDescriptorLocation = MULE_AUTO_GENERATED_ARTIFACT_PATH_INSIDE_JAR;
      }
      logger.debug("Loading plugin '{}' descriptor (from path: {}) using classloader {}", pluginUrlClassification.getName(),
                   pluginDescriptorLocation, classLoader.getURLs());

      MulePluginModel mulePluginModel;
      try {
        mulePluginModel = new MulePluginModelJsonSerializer()
            .deserialize(IOUtils.toString(classLoader.getResourceAsStream(pluginDescriptorLocation)));
      } catch (IOException e) {
        throw new IllegalArgumentException(format("Could not read extension describer on plugin '%s' from JAR with MD5 '%s'",
                                                  pluginJsonUrl, TroubleshootingUtils.getMD5FromFile(pluginJsonUrl)),
                                           e);
      }

      Map<String, Object> attributes = mulePluginModel.getClassLoaderModelLoaderDescriptor().getAttributes();
      exportPackages.addAll((List<String>) attributes.getOrDefault(EXPORTED_PACKAGES, emptyList()));
      exportResources.addAll((List<String>) attributes.getOrDefault(EXPORTED_RESOURCES, emptyList()));

      privilegedExportedPackages.addAll((List<String>) attributes.getOrDefault(PRIVILEGED_EXPORTED_PACKAGES, emptyList()));
      privilegedArtifacts.addAll((List<String>) attributes.getOrDefault(PRIVILEGED_ARTIFACTS_IDS, emptyList()));

      return new PluginUrlClassification(pluginUrlClassification.getName(), pluginUrlClassification.getUrls(),
                                         pluginUrlClassification.getExportClasses(),
                                         pluginUrlClassification.getPluginDependencies(), exportPackages, exportResources,
                                         privilegedExportedPackages, privilegedArtifacts);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
