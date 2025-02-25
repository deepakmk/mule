/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.module.service.api.artifact;

import org.mule.runtime.module.artifact.api.classloader.ArtifactClassLoader;
import org.mule.runtime.module.artifact.api.classloader.ArtifactClassLoaderFactory;
import org.mule.runtime.module.artifact.api.classloader.ClassLoaderLookupPolicy;
import org.mule.runtime.module.artifact.api.classloader.MuleArtifactClassLoader;

/**
 * Creates {@link ArtifactClassLoader} for service descriptors.
 */
public class ServiceClassLoaderFactory implements ArtifactClassLoaderFactory<ServiceDescriptor> {

  /**
   * @inherited
   */
  @Override
  public ArtifactClassLoader create(String artifactId, ServiceDescriptor descriptor, ClassLoader parent,
                                    ClassLoaderLookupPolicy lookupPolicy) {
    return new MuleArtifactClassLoader(artifactId, descriptor, descriptor.getClassLoaderConfiguration().getUrls(), parent,
                                       lookupPolicy);
  }
}
