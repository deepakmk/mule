/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.loader.java.enricher;

import static java.util.Optional.of;
import static org.mule.metadata.api.model.MetadataFormat.JAVA;
import static org.mule.runtime.api.meta.ExpressionSupport.SUPPORTED;
import static org.mule.runtime.api.meta.model.parameter.ParameterGroupModel.DEFAULT_GROUP_NAME;
import static org.mule.runtime.extension.api.loader.DeclarationEnricherPhase.STRUCTURE;
import static org.mule.runtime.extension.api.util.ExtensionMetadataTypeUtils.toMetadataFormat;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.ADVANCED_TAB_NAME;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.ENCODING_PARAMETER_NAME;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.MIME_TYPE_PARAMETER_NAME;
import static org.mule.runtime.module.extension.internal.loader.utils.JavaModelLoaderUtils.isInputStream;

import org.mule.metadata.api.ClassTypeLoader;
import org.mule.metadata.api.annotation.EnumAnnotation;
import org.mule.metadata.api.annotation.TypeAnnotation;
import org.mule.metadata.api.builder.AnyTypeBuilder;
import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.api.builder.BinaryTypeBuilder;
import org.mule.metadata.api.builder.StringTypeBuilder;
import org.mule.metadata.api.builder.WithAnnotation;
import org.mule.metadata.api.model.AnyType;
import org.mule.metadata.api.model.ArrayType;
import org.mule.metadata.api.model.BinaryType;
import org.mule.metadata.api.model.MetadataFormat;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.StringType;
import org.mule.metadata.api.visitor.MetadataTypeVisitor;
import org.mule.metadata.message.api.MessageMetadataType;
import org.mule.runtime.api.meta.model.ModelProperty;
import org.mule.runtime.api.meta.model.declaration.fluent.ExecutableComponentDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.OperationDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.OutputDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.ParameterDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.ParameterGroupDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.SourceDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.WithSourcesDeclaration;
import org.mule.runtime.api.meta.model.display.LayoutModel;
import org.mule.runtime.extension.api.declaration.type.ExtensionsTypeLoaderFactory;
import org.mule.runtime.extension.api.loader.DeclarationEnricher;
import org.mule.runtime.extension.api.loader.DeclarationEnricherPhase;
import org.mule.runtime.extension.api.loader.ExtensionLoadingContext;
import org.mule.runtime.extension.api.loader.IdempotentDeclarationEnricherWalkDelegate;
import org.mule.runtime.extension.api.loader.WalkingDeclarationEnricher;
import org.mule.runtime.extension.api.property.SinceMuleVersionModelProperty;
import org.mule.runtime.module.extension.api.loader.java.type.OperationElement;
import org.mule.runtime.module.extension.api.loader.java.type.SourceElement;
import org.mule.runtime.module.extension.internal.ExtensionProperties;
import org.mule.runtime.module.extension.internal.loader.annotations.CustomDefinedStaticTypeAnnotation;
import org.mule.runtime.module.extension.internal.loader.java.property.MediaTypeModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.type.property.ExtensionOperationDescriptorModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.type.property.ExtensionTypeDescriptorModelProperty;

import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Enriches operations which return types are {@link InputStream}, {@link String} or {@link Object} by adding two parameters: A
 * {@link ExtensionProperties#MIME_TYPE_PARAMETER_NAME} that allows configuring the mimeType to the output operation payload and a
 * {@link ExtensionProperties#ENCODING_PARAMETER_NAME} that allows configuring the encoding to the output operation payload.
 * <p>
 * Both added attributes are optional without default value and accept expressions.
 *
 * @since 4.0
 */
public final class JavaMimeTypeParametersDeclarationEnricher implements WalkingDeclarationEnricher {

  /**
   * Certain enrichments of this {@link DeclarationEnricher} where added in a newer version of mule than the one when it was
   * introduced. This {@link ModelProperty} has to be added to these new enrichments so that if the
   * {@link org.mule.runtime.api.meta.model.ExtensionModel} of an extension need to be recreated for a former muleVersion, the new
   * enrichments can be filtered.
   */
  private static SinceMuleVersionModelProperty SINCE_MULE_VERSION_MODEL_PROPERTY = new SinceMuleVersionModelProperty("4.2.0");

  @Override
  public DeclarationEnricherPhase getExecutionPhase() {
    return STRUCTURE;
  }

  @Override
  public Optional<DeclarationEnricherWalkDelegate> getWalkDelegate(ExtensionLoadingContext extensionLoadingContext) {
    return of(new IdempotentDeclarationEnricherWalkDelegate() {

      private final ClassTypeLoader typeLoader = ExtensionsTypeLoaderFactory.getDefault().createTypeLoader();

      @Override
      protected void onOperation(OperationDeclaration declaration) {
        Optional<MetadataType> outputType = declaration.getModelProperty(ExtensionOperationDescriptorModelProperty.class)
            .map(ExtensionOperationDescriptorModelProperty::getOperationElement)
            .map(OperationElement::getOperationReturnMetadataType);
        declareMimeTypeParameters(declaration, outputType);
      }

      @Override
      public void onSource(WithSourcesDeclaration owner, SourceDeclaration declaration) {
        Optional<MetadataType> outputType = declaration.getModelProperty(ExtensionTypeDescriptorModelProperty.class)
            .filter(mp -> mp.getType() instanceof SourceElement)
            .map(mp -> (SourceElement) mp.getType())
            .map(SourceElement::getReturnMetadataType);
        declareMimeTypeParameters(declaration, outputType);
      }

      private void declareOutputEncodingParameter(ParameterGroupDeclaration group,
                                                  boolean shouldAddSinceMuleVersionModelProperty) {
        group.addParameter(newParameter(ENCODING_PARAMETER_NAME, "The encoding of the payload that this operation outputs.",
                                        shouldAddSinceMuleVersionModelProperty));
      }

      private void declareOutputMimeTypeParameter(ParameterGroupDeclaration group,
                                                  boolean shouldAddSinceMuleVersionModelProperty) {
        group.addParameter(newParameter(MIME_TYPE_PARAMETER_NAME, "The mime type of the payload that this operation outputs.",
                                        shouldAddSinceMuleVersionModelProperty));
      }

      private void declareOutputEncodingParameter(ParameterGroupDeclaration group) {
        declareOutputEncodingParameter(group, false);
      }

      private void declareOutputMimeTypeParameter(ParameterGroupDeclaration group) {
        declareOutputMimeTypeParameter(group, false);
      }

      private void declareMimeTypeParameters(ExecutableComponentDeclaration<?> declaration, Optional<MetadataType> outputType) {
        MediaTypeModelProperty property = declaration.getModelProperty(MediaTypeModelProperty.class).orElse(null);
        outputType.orElse(declaration.getOutput().getType()).accept(new MetadataTypeVisitor() {

          @Override
          public void visitString(StringType stringType) {
            if (property == null || stringType.getAnnotation(EnumAnnotation.class).isPresent()) {
              return;
            }


            if (!property.isStrict()) {
              declareOutputMimeTypeParameter(declaration.getParameterGroup(DEFAULT_GROUP_NAME));
            }

            replaceOutputType(declaration, property, format -> {
              StringTypeBuilder stringTypeBuilder = BaseTypeBuilder.create(format).stringType();
              enrichWithAnnotations(stringTypeBuilder, declaration.getOutput().getType().getAnnotations());
              return stringTypeBuilder.build();
            });
          }

          @Override
          public void visitArrayType(ArrayType arrayType) {

            MetadataType itemType = arrayType.getType();

            if (itemType instanceof MessageMetadataType) {
              itemType = ((MessageMetadataType) itemType).getPayloadType().orElse(null);
              if (itemType == null) {
                return;
              }
            }

            if (itemType instanceof StringType && !itemType.getAnnotation(EnumAnnotation.class).isPresent()) {
              ParameterGroupDeclaration group = declaration.getParameterGroup(DEFAULT_GROUP_NAME);
              declareOutputMimeTypeParameter(group, true);
            } else if (itemType instanceof BinaryType || itemType instanceof AnyType) {
              ParameterGroupDeclaration group = declaration.getParameterGroup(DEFAULT_GROUP_NAME);
              declareOutputMimeTypeParameter(group, true);
              declareOutputEncodingParameter(group, true);
            }
          }

          @Override
          public void visitBinaryType(BinaryType binaryType) {
            if (property == null) {
              return;
            }

            if (!property.isStrict()) {
              ParameterGroupDeclaration group = declaration.getParameterGroup(DEFAULT_GROUP_NAME);
              declareOutputMimeTypeParameter(group);
              declareOutputEncodingParameter(group);
            }

            replaceOutputType(declaration, property, format -> {
              BinaryTypeBuilder builder = BaseTypeBuilder.create(format).binaryType();
              enrichWithAnnotations(builder, declaration.getOutput().getType().getAnnotations());

              return builder.build();
            });
          }

          @Override
          public void visitAnyType(AnyType anyType) {
            if (property == null) {
              return;
            }

            if (!property.isStrict()) {
              boolean shouldAddSinceMuleVersionModelProperty = !isInputStream(anyType);
              ParameterGroupDeclaration group = declaration.getParameterGroup(DEFAULT_GROUP_NAME);
              declareOutputMimeTypeParameter(group, shouldAddSinceMuleVersionModelProperty);
              declareOutputEncodingParameter(group, shouldAddSinceMuleVersionModelProperty);
            }

            replaceOutputType(declaration, property, format -> {
              AnyTypeBuilder anyTypeBuilder = BaseTypeBuilder.create(format).anyType();
              enrichWithAnnotations(anyTypeBuilder, declaration.getOutput().getType().getAnnotations());
              return anyTypeBuilder.build();
            });

          }

          private void enrichWithAnnotations(WithAnnotation withAnnotationBuilder, Set<TypeAnnotation> annotations) {
            annotations.forEach(typeAnnotation -> withAnnotationBuilder.with(typeAnnotation));
          }
        });
      }

      private void replaceOutputType(ExecutableComponentDeclaration<?> declaration, MediaTypeModelProperty property,
                                     Function<MetadataFormat, MetadataType> type) {
        if (!shouldOverrideMetadataFormat(declaration)) {
          return;
        }

        property.getMediaType().ifPresent(mediaType -> {
          final OutputDeclaration output = declaration.getOutput();
          output.setType(type.apply(toMetadataFormat(mediaType)), output.hasDynamicType());
        });
      }


      private boolean shouldOverrideMetadataFormat(ExecutableComponentDeclaration declaration) {
        /**
         * On top of looking for the CustomDefinedStaticTypeAnnotation to see if there is a Static Resolution involved, you have
         * to check that the MetadataFormat is not JAVA, since there are other ways of Static Resolved Metadata that do not add
         * this annotation (none of the set the metadata format as JAVA), for example: @OutputJsonType and @OutputXmlType.
         */
        return !declaration.getOutput().getType().getAnnotation(CustomDefinedStaticTypeAnnotation.class).isPresent() &&
            declaration.getOutput().getType().getMetadataFormat().equals(JAVA);
      }

      private ParameterDeclaration newParameter(String name, String description) {
        return newParameter(name, description, false);
      }

      private ParameterDeclaration newParameter(String name, String description, boolean shouldAddSinceMuleVersionModelProperty) {
        ParameterDeclaration parameter = new ParameterDeclaration(name);
        parameter.setRequired(false);
        parameter.setExpressionSupport(SUPPORTED);
        parameter.setType(typeLoader.load(String.class), false);
        parameter.setDescription(description);
        parameter.setLayoutModel(LayoutModel.builder().tabName(ADVANCED_TAB_NAME).build());
        if (shouldAddSinceMuleVersionModelProperty) {
          parameter.addModelProperty(SINCE_MULE_VERSION_MODEL_PROPERTY);
        }
        return parameter;
      }
    });
  }
}
