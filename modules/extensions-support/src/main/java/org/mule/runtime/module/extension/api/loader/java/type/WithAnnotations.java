/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.api.loader.java.type;

import org.mule.api.annotation.NoImplement;

import java.lang.annotation.Annotation;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A generic contract for any kind of component that can be annotated
 *
 * @since 4.0
 */
@NoImplement
public interface WithAnnotations {

  /**
   * Retrieves an annotation of the {@link WithAnnotations} component
   *
   * @param annotationClass Of the annotation to retrieve
   * @param <A>             The annotation type
   * @return The {@link Optional} annotation to retrieve
   * @deprecated since 4.5.0. Use {@link #getValueFromAnnotation(Class)} instead
   */
  @Deprecated
  <A extends Annotation> Optional<A> getAnnotation(Class<A> annotationClass);

  /**
   * Returns an optional {@link AnnotationValueFetcher} which encapsulates the logic of obtaining annotations values when
   * executing with classes or with the Java AST.
   *
   * @param annotationClass Of the annotation to retrieve
   * @param <A>             The annotation type
   * @return The {@link Optional} {@link AnnotationValueFetcher} to retrieve
   * @since 4.1
   */
  <A extends Annotation> Optional<AnnotationValueFetcher<A>> getValueFromAnnotation(Class<A> annotationClass);

  /**
   * @param annotation The annotation to verify if the, {@link WithAnnotations} is annotated with.
   * @return A {@code boolean} indicating if the {@link WithAnnotations} element is annotated with the given {@code annotation}
   */
  default boolean isAnnotatedWith(Class<? extends Annotation> annotation) {
    return getValueFromAnnotation(annotation).isPresent();
  }

  /**
   * Returns all the annotations present within the component as a {@link Type}
   *
   * @return A {@code List} of the annotations as a {@link Type}
   */
  Stream<Type> getAnnotations();
}
