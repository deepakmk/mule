/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.loader.java.enricher;

import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.extractImplementingTypeProperty;

import org.mule.runtime.api.meta.model.ModelProperty;
import org.mule.runtime.api.meta.model.declaration.fluent.BaseDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.ExtensionDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.OperationDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.SourceDeclaration;
import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.loader.DeclarationEnricher;
import org.mule.runtime.extension.api.loader.ExtensionLoadingContext;
import org.mule.runtime.extension.api.loader.IdempotentDeclarationEnricherWalkDelegate;
import org.mule.runtime.extension.api.loader.WalkingDeclarationEnricher;
import org.mule.runtime.module.extension.api.loader.java.type.ExtensionParameter;
import org.mule.runtime.module.extension.api.loader.java.type.SourceElement;
import org.mule.runtime.module.extension.api.loader.java.type.WithParameters;
import org.mule.runtime.module.extension.internal.loader.java.property.ConfigTypeModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.property.ConnectivityModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.type.property.ExtensionOperationDescriptorModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.type.property.ExtensionTypeDescriptorModelProperty;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * {@link DeclarationEnricher} implementation that walks through a {@link ExtensionDeclaration} and looks for annotated component
 * parameters (Sources and Operations), with {@link Connection} and adds a {@link ConnectivityModelProperty} or annotated with
 * {@link Config} and adds a {@link ConfigTypeModelProperty}
 *
 * @since 4.0
 */
public class JavaConfigurationDeclarationEnricher implements WalkingDeclarationEnricher {

  @Override
  public Optional<DeclarationEnricherWalkDelegate> getWalkDelegate(ExtensionLoadingContext extensionLoadingContext) {
    return extractImplementingTypeProperty(extensionLoadingContext.getExtensionDeclarer().getDeclaration())
        .map(p -> new IdempotentDeclarationEnricherWalkDelegate() {

          @Override
          protected void onOperation(OperationDeclaration declaration) {
            enrich(declaration, ExtensionOperationDescriptorModelProperty.class,
                   (operation, property) -> enrich(operation, property.getOperationElement()));
          }

          @Override
          public void onSource(SourceDeclaration declaration) {
            enrich(declaration, ExtensionTypeDescriptorModelProperty.class,
                   (source, property) -> enrich(source, (SourceElement) property.getType()));
          }
        });
  }

  private <P extends ModelProperty> void enrich(BaseDeclaration declaration, Class<P> propertyType,
                                                BiConsumer<BaseDeclaration, P> consumer) {
    declaration.getModelProperty(propertyType).ifPresent(p -> consumer.accept(declaration, (P) p));
  }

  private void enrich(BaseDeclaration declaration, WithParameters methodWrapper) {
    final List<ExtensionParameter> configParameters = methodWrapper.getParametersAnnotatedWith(Config.class);
    if (!configParameters.isEmpty()) {
      configParameters.get(0).getType().getDeclaringClass()
          .ifPresent(declaringClass -> declaration.addModelProperty(new ConfigTypeModelProperty(declaringClass)));

    }
  }
}
