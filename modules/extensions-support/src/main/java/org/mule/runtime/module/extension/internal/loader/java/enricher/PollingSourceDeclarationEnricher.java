/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.loader.java.enricher;

import static java.util.Optional.of;
import static org.mule.metadata.api.model.MetadataFormat.JAVA;
import static org.mule.metadata.api.utils.MetadataTypeUtils.getTypeId;
import static org.mule.runtime.api.meta.ExpressionSupport.NOT_SUPPORTED;
import static org.mule.runtime.api.meta.model.parameter.ParameterGroupModel.DEFAULT_GROUP_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.POLLING_SOURCE_LIMIT_PARAMETER_DESCRIPTION;
import static org.mule.runtime.extension.api.ExtensionConstants.POLLING_SOURCE_LIMIT_PARAMETER_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.SCHEDULING_STRATEGY_PARAMETER_DESCRIPTION;
import static org.mule.runtime.extension.api.ExtensionConstants.SCHEDULING_STRATEGY_PARAMETER_NAME;
import static org.mule.runtime.extension.api.annotation.param.display.Placement.ADVANCED_TAB;
import static org.mule.runtime.extension.api.loader.DeclarationEnricherPhase.STRUCTURE;
import static org.mule.runtime.internal.dsl.DslConstants.CORE_NAMESPACE;
import static org.mule.runtime.internal.dsl.DslConstants.CORE_PREFIX;
import static org.mule.runtime.internal.dsl.DslConstants.SCHEDULING_STRATEGY_ELEMENT_IDENTIFIER;
import static org.mule.runtime.module.extension.api.util.MuleExtensionUtils.isPollingSourceLimitEnabled;
import static org.mule.runtime.module.extension.internal.loader.java.contributor.InfrastructureTypeResolver.getInfrastructureType;

import org.mule.metadata.api.ClassTypeLoader;
import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.metadata.api.model.UnionType;
import org.mule.runtime.api.meta.model.ImportedTypeModel;
import org.mule.runtime.api.meta.model.ParameterDslConfiguration;
import org.mule.runtime.api.meta.model.declaration.fluent.ExtensionDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.ExtensionDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.ParameterDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.SourceDeclaration;
import org.mule.runtime.api.meta.model.display.LayoutModel;
import org.mule.runtime.api.scheduler.SchedulingStrategy;
import org.mule.runtime.extension.api.declaration.type.DefaultExtensionsTypeLoaderFactory;
import org.mule.runtime.extension.api.declaration.type.ExtensionsTypeLoaderFactory;
import org.mule.runtime.extension.api.loader.DeclarationEnricher;
import org.mule.runtime.extension.api.loader.DeclarationEnricherPhase;
import org.mule.runtime.extension.api.loader.ExtensionLoadingContext;
import org.mule.runtime.extension.api.loader.IdempotentDeclarationEnricherWalkDelegate;
import org.mule.runtime.extension.api.loader.WalkingDeclarationEnricher;
import org.mule.runtime.extension.api.property.InfrastructureParameterModelProperty;
import org.mule.runtime.extension.api.property.QNameModelProperty;
import org.mule.runtime.extension.api.property.SinceMuleVersionModelProperty;
import org.mule.runtime.extension.api.property.SyntheticModelModelProperty;
import org.mule.runtime.extension.api.runtime.source.PollingSource;
import org.mule.runtime.module.extension.internal.loader.java.type.property.ExtensionTypeDescriptorModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.type.runtime.TypeWrapper;

import java.util.Optional;

import javax.xml.namespace.QName;

/**
 * {@link DeclarationEnricher} for {@link PollingSource polling sources}
 *
 * @since 4.1
 */
public class PollingSourceDeclarationEnricher implements WalkingDeclarationEnricher {

  private static final String POLLING_SOURCE_LIMIT_MULE_VERSION = "4.4.0";

  @Override
  public Optional<DeclarationEnricherWalkDelegate> getWalkDelegate(ExtensionLoadingContext extensionLoadingContext) {
    return of(new IdempotentDeclarationEnricherWalkDelegate() {

      final int schedulingStrategyParameterSequence =
          getInfrastructureType(new TypeWrapper(SchedulingStrategy.class,
                                                new DefaultExtensionsTypeLoaderFactory()
                                                    .createTypeLoader(extensionLoadingContext.getExtensionClassLoader())))
                                                        .map(infrastructureType -> infrastructureType.getSequence()).orElse(0);
      ClassTypeLoader loader = ExtensionsTypeLoaderFactory.getDefault().createTypeLoader();
      ExtensionDeclarer extensionDeclarer = extensionLoadingContext.getExtensionDeclarer();
      boolean thereArePollingSources = false;

      @Override
      protected void onSource(SourceDeclaration source) {
        source.getModelProperty(ExtensionTypeDescriptorModelProperty.class)
            .map(ExtensionTypeDescriptorModelProperty::getType)
            .ifPresent(type -> {
              if (type.isAssignableTo(PollingSource.class)
                  || type.isAssignableTo(org.mule.sdk.api.runtime.source.PollingSource.class)) {

                thereArePollingSources = true;

                source.getParameterGroup(DEFAULT_GROUP_NAME).addParameter(declareSchedulingStrategyParameter(loader));

                if (isPollingSourceLimitEnabled(extensionLoadingContext)) {
                  source.getParameterGroup(DEFAULT_GROUP_NAME).addParameter(declarePollingSourceLimitParameter());
                }
              }
            });
      }

      private ParameterDeclaration declarePollingSourceLimitParameter() {
        ParameterDeclaration parameter = new ParameterDeclaration(POLLING_SOURCE_LIMIT_PARAMETER_NAME);
        parameter.setDescription(POLLING_SOURCE_LIMIT_PARAMETER_DESCRIPTION);
        parameter.setRequired(false);
        parameter.setType(BaseTypeBuilder.create(JAVA).numberType().integer().range(1, null).build(), false);
        parameter.setExpressionSupport(NOT_SUPPORTED);
        parameter.addModelProperty(new SyntheticModelModelProperty());
        parameter.addModelProperty(new SinceMuleVersionModelProperty(POLLING_SOURCE_LIMIT_MULE_VERSION));
        parameter.setLayoutModel(LayoutModel.builder().tabName(ADVANCED_TAB).build());

        return parameter;
      }

      private ParameterDeclaration declareSchedulingStrategyParameter(ClassTypeLoader loader) {
        ParameterDeclaration parameter = new ParameterDeclaration(SCHEDULING_STRATEGY_PARAMETER_NAME);
        parameter.setDescription(SCHEDULING_STRATEGY_PARAMETER_DESCRIPTION);
        parameter.setRequired(true);
        parameter.setType(loadSchedulingStrategyType(loader), false);
        parameter.setExpressionSupport(NOT_SUPPORTED);
        parameter.addModelProperty(new InfrastructureParameterModelProperty(schedulingStrategyParameterSequence));
        parameter.addModelProperty(new QNameModelProperty(new QName(CORE_NAMESPACE, SCHEDULING_STRATEGY_ELEMENT_IDENTIFIER,
                                                                    CORE_PREFIX)));
        parameter.setDslConfiguration(ParameterDslConfiguration.builder()
            .allowsInlineDefinition(true)
            .allowsReferences(false)
            .allowTopLevelDefinition(false)
            .build());

        return parameter;
      }

      @Override
      public void onWalkFinished() {
        if (thereArePollingSources && !isSchedulerAlreadyImported(extensionDeclarer.getDeclaration())) {
          ClassTypeLoader typeLoader = ExtensionsTypeLoaderFactory.getDefault().createTypeLoader();
          extensionDeclarer.withImportedType(new ImportedTypeModel((ObjectType) loadSchedulingStrategyType(typeLoader)));
        }
      }
    });
  }

  /**
   * This method is just loading the {@link SchedulingStrategy} type from the core extension model, so it doesn't need to declare
   * the subtypes ({@code cron} and {@code fixed-frequency}).
   *
   * <p>
   * It isn't implemented as an {@link UnionType} because of backwards compatibility (see MULE-19167 and
   * {@code org.mule.runtime.core.api.extension.MuleExtensionModelDeclarer}).
   *
   * @param loader The type loader.
   * @return The {@code scheduling-strategy} parameter type.
   */
  private MetadataType loadSchedulingStrategyType(ClassTypeLoader loader) {
    return loader.load(SchedulingStrategy.class);
  }

  private boolean isSchedulerAlreadyImported(ExtensionDeclaration extension) {
    return extension.getImportedTypes().stream().anyMatch(model -> isScheduler(model.getImportedType()));
  }

  private boolean isScheduler(MetadataType type) {
    return getTypeId(type)
        .filter(typeId -> SchedulingStrategy.class.getName().equals(typeId))
        .isPresent();
  }

  @Override
  public DeclarationEnricherPhase getExecutionPhase() {
    return STRUCTURE;
  }
}
