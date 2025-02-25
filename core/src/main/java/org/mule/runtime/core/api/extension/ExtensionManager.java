/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.api.extension;

import org.mule.api.annotation.NoImplement;
import org.mule.runtime.api.event.Event;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.Stoppable;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.runtime.config.ConfigurationProvider;
import org.mule.runtime.extension.api.runtime.config.ConfigurationStats;

import java.util.Optional;
import java.util.Set;

/**
 * Manages the {@link ExtensionModel extension models} available in the current context and their state.
 * <p>
 * This class is also the access point to obtaining {@link ConfigurationInstance configuration instances} of the extensions in
 * use.
 * <p>
 * For an extension to be usable, it has to be registered in this manager through the {@link #registerExtension(ExtensionModel)}
 * method
 *
 * @since 4.0
 */
@NoImplement
public interface ExtensionManager {

  /**
   * Registers the given {@link ExtensionModel}.
   *
   * @param extensionModel the {@link ExtensionModel} to be registered. Cannot be {@code null}
   */
  void registerExtension(ExtensionModel extensionModel);

  /**
   * Returns an immutable {@link Set} listing all the discovered {@link ExtensionModel extensionModels}.
   *
   * @return an immutable {@link Set}. Will not be {@code null} but might be empty
   */
  Set<ExtensionModel> getExtensions();

  /**
   * Returns an {@link Optional} {@link ExtensionModel} which name equals {@code extensionName}.
   *
   * @param extensionName the name of the extensions you want.
   * @return an {@link Optional}. It will be empty if no such extension is registered
   */
  Optional<ExtensionModel> getExtension(String extensionName);

  /**
   * Returns a {@link ConfigurationInstance} obtained through a previously registered {@link ConfigurationProvider} named as
   * {@code configurationProvider}
   * <p>
   * After the {@link ConfigurationProvider} has been located, an instance is returned by invoking its
   * {@link ConfigurationProvider#get(Event)} with the {@code muleEvent} as the argument.
   * <p>
   * By the mere fact of this configuration being returned, the value of {@link ConfigurationStats#getLastUsedMillis()} will be
   * updated for the returned {@link ConfigurationInstance}
   *
   * @param configurationProviderName the name of a previously registered {@link ConfigurationProvider}
   * @param event                     the current Event
   * @return a {@link ConfigurationInstance}
   */
  ConfigurationInstance getConfiguration(String configurationProviderName, CoreEvent event);

  /**
   * Delegates into {@link #getConfigurationProvider(ExtensionModel, ComponentModel, CoreEvent)} to locate a suitable provider and
   * uses the given {@code muleEvent} to obtain a {@link ConfigurationInstance} out of it.
   * <p>
   * By the mere fact of this configuration being returned, the value of {@link ConfigurationStats#getLastUsedMillis()} will be
   * updated for the returned {@link ConfigurationInstance}
   *
   * @param extensionModel the {@link ExtensionModel} for which a configuration is wanted
   * @param componentModel the {@link ComponentModel} associated to a {@link ConfigurationInstance}
   * @param muleEvent      the current Event
   * @return an {@link Optional} for a {@link ConfigurationInstance}
   * @throws IllegalStateException if none or too many {@link ConfigurationProvider} are found to be suitable
   */
  Optional<ConfigurationInstance> getConfiguration(ExtensionModel extensionModel,
                                                   ComponentModel componentModel,
                                                   CoreEvent muleEvent);

  /**
   * Returns an optional {@link ConfigurationProvider} for the given {@code extensionModel} and {@code componentModel}.
   * <p>
   * Because no {@link ConfigurationProvider} is specified, the following algorithm will be applied to try and determine the
   * instance to be returned:
   * <ul>
   * <li>If <b>one</b> (and only one) {@link ConfigurationProvider} is registered, capable of handing configurations of the given
   * {@code componentModel}, then that provider is used</li>
   * <li>If more than one {@link ConfigurationProvider} meeting the criteria above is found, then a {@link IllegalStateException}
   * is thrown</li>
   * <li>If no such {@link ConfigurationProvider} is registered, then an attempt will be made to locate a
   * {@link ConfigurationModel} for which an implicit configuration can be inferred. A model can be considered implicit if all its
   * parameters are either optional or provide a default value. If such a model is found and it is unique, then a
   * {@link ConfigurationProvider} is created and registered for that model.</li>
   * <li>If none of the above conditions is met, then an {@link IllegalStateException} is thrown</li>
   * </ul>
   *
   * @param extensionModel the {@link ExtensionModel} for which a configuration is wanted
   * @param componentModel the {@link ComponentModel} associated to a {@link ConfigurationInstance}
   * @param muleEvent      the current Event
   * @return an {@link Optional} for a {@link ConfigurationProvider}
   * @throws IllegalStateException if none or too many {@link ConfigurationProvider} are found to be suitable
   * @since 4.3.0
   */
  Optional<ConfigurationProvider> getConfigurationProvider(ExtensionModel extensionModel,
                                                           ComponentModel componentModel,
                                                           CoreEvent muleEvent);

  /**
   * Locates and returns the {@link ConfigurationProvider} which would serve an invocation to the
   * {@link #getConfiguration(String, CoreEvent)} method.
   * <p>
   * This means that the returned provided will be located using the same set of rules as the aforementioned method.
   *
   * @param configurationProviderName the name of a previously registered {@link ConfigurationProvider}
   * @return an {@link Optional} {@link ConfigurationProvider}
   */
  Optional<ConfigurationProvider> getConfigurationProvider(String configurationProviderName);

  /**
   * Locates and returns (if there is any) a suitable {@link ConfigurationProvider} for the given {@link ComponentModel}.
   *
   * @param extensionModel the {@link ExtensionModel} for which a configuration is wanted
   * @param componentModel the {@link ComponentModel} for which a configuration is wanted
   * @return an {@link Optional} {@link ConfigurationProvider}
   */
  Optional<ConfigurationProvider> getConfigurationProvider(ExtensionModel extensionModel,
                                                           ComponentModel componentModel);

  /**
   * Registered the given {@link ConfigurationProvider} which should be later be used to serve invocations to
   * {@link #getConfigurationProvider(ExtensionModel, ComponentModel)} and {@link #getConfiguration(String, CoreEvent)}
   *
   * @param configurationProvider a {@link ConfigurationProvider}
   */
  void registerConfigurationProvider(ConfigurationProvider configurationProvider);

  /**
   * Unregisters the given {@code configurationProvider} which must have previously registered through the
   * {@link #registerExtension(ExtensionModel)} method. {@link Stoppable} and {@link Disposable} lifecycle phases might be
   * executed on the provider depending on the Mule's current lifecycle state.
   *
   * @param configurationProvider a {@link ConfigurationProvider}
   * @throws IllegalArgumentException if {@code configurationProvider} is {@code null}
   * @since 4.5.0
   */
  void unregisterConfigurationProvider(ConfigurationProvider configurationProvider);

  /**
   * Performs actions related to the disposal of the configuration in the extension manager
   *
   * @param key           the key for the configuration to be disposed
   * @param configuration the configuration to be disposed.
   */
  default void disposeConfiguration(String key, ConfigurationInstance configuration) {}

}
