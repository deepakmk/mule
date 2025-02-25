/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static java.lang.Boolean.parseBoolean;
import static java.util.Collections.singleton;
import static java.util.Optional.empty;
import static org.hamcrest.CoreMatchers.notNullValue;

import static org.mule.runtime.api.component.AbstractComponent.ANNOTATION_NAME;
import static org.mule.runtime.api.component.AbstractComponent.LOCATION_KEY;
import static org.mule.runtime.api.config.MuleRuntimeFeature.ENABLE_PROFILING_SERVICE;
import static org.mule.runtime.api.notification.MessageProcessorNotification.MESSAGE_PROCESSOR_POST_INVOKE;
import static org.mule.runtime.api.notification.MessageProcessorNotification.MESSAGE_PROCESSOR_PRE_INVOKE;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.FLOW_EXECUTED;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.PS_OPERATION_EXECUTED;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.PS_FLOW_MESSAGE_PASSING;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.PS_SCHEDULING_FLOW_EXECUTION;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.PS_SCHEDULING_OPERATION_EXECUTION;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.STARTING_FLOW_EXECUTION;
import static org.mule.runtime.api.profiling.type.RuntimeProfilingEventTypes.PS_STARTING_OPERATION_EXECUTION;
import static org.mule.runtime.core.api.construct.Flow.builder;
import static org.mule.runtime.core.api.error.Errors.Identifiers.FLOW_BACK_PRESSURE_ERROR_IDENTIFIER;
import static org.mule.runtime.core.api.event.EventContextFactory.create;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.setMuleContextIfNeeded;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.BLOCKING;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.CPU_LITE;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.CPU_LITE_ASYNC;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.IO_RW;
import static org.mule.runtime.core.api.rx.Exceptions.rxExceptionToMuleException;
import static org.mule.runtime.core.api.transaction.TransactionCoordination.getInstance;
import static org.mule.runtime.core.internal.processor.strategy.AbstractProcessingStrategy.PROCESSOR_SCHEDULER_CONTEXT_KEY;
import static org.mule.runtime.core.internal.processor.strategy.AbstractProcessingStrategyTestCase.Mode.FLOW;
import static org.mule.runtime.core.internal.processor.strategy.AbstractProcessingStrategyTestCase.Mode.SOURCE;
import static org.mule.runtime.core.internal.processor.strategy.AbstractStreamProcessingStrategyFactory.CORES;
import static org.mule.runtime.core.internal.profiling.NoopCoreEventTracer.getNoopCoreEventTracer;
import static org.mule.runtime.core.internal.util.rx.Operators.requestUnbounded;
import static org.mule.runtime.dsl.api.component.config.DefaultComponentLocation.from;
import static org.mule.tck.probe.PollingProber.DEFAULT_POLLING_INTERVAL;
import static org.mule.tck.probe.PollingProber.DEFAULT_TIMEOUT;
import static org.mule.tck.util.MuleContextUtils.getNotificationDispatcher;

import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static java.util.Collections.synchronizedSet;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.Exceptions.bubble;
import static reactor.core.Exceptions.propagate;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Mono.just;
import static reactor.core.scheduler.Schedulers.fromExecutorService;

import org.mule.runtime.api.component.AbstractComponent;
import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.notification.IntegerAction;
import org.mule.runtime.api.notification.MessageProcessorNotification;
import org.mule.runtime.api.notification.MessageProcessorNotificationListener;
import org.mule.runtime.api.profiling.ProfilingDataConsumer;
import org.mule.runtime.api.profiling.ProfilingDataConsumerDiscoveryStrategy;
import org.mule.runtime.api.profiling.ProfilingService;
import org.mule.runtime.api.profiling.type.context.ComponentProcessingStrategyProfilingEventContext;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.util.concurrent.Latch;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.construct.Flow.Builder;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategyFactory;
import org.mule.runtime.core.api.source.MessageSource.BackPressureStrategy;
import org.mule.runtime.core.api.util.concurrent.NamedThreadFactory;
import org.mule.runtime.core.internal.construct.FlowBackPressureException;
import org.mule.runtime.core.internal.context.MuleContextWithRegistry;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.message.InternalEvent;
import org.mule.runtime.core.internal.profiling.DefaultProfilingService;
import org.mule.runtime.core.internal.util.rx.RetrySchedulerWrapper;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.core.privileged.processor.AnnotatedProcessor;
import org.mule.runtime.core.privileged.processor.InternalProcessor;
import org.mule.runtime.core.privileged.registry.RegistrationException;
import org.mule.runtime.feature.internal.config.profiling.ProfilingFeatureFlaggingService;
import org.mule.runtime.tracer.api.EventTracer;
import org.mule.tck.TriggerableMessageSource;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.junit4.rule.SystemProperty;
import org.mule.tck.probe.JUnitLambdaProbe;
import org.mule.tck.probe.PollingProber;
import org.mule.tck.testmodels.mule.TestTransaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableMap;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.junit.Assert;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

@RunWith(Parameterized.class)
public abstract class AbstractProcessingStrategyTestCase extends AbstractMuleContextTestCase {

  private static final Logger LOGGER = getLogger(AbstractProcessingStrategyTestCase.class);
  private static final int CONCURRENT_TEST_CONCURRENCY = 8;
  protected final ProfilingDataConsumer<ComponentProcessingStrategyProfilingEventContext> profilingDataConsumer =
      mock(ProfilingDataConsumer.class);
  private final boolean profiling;

  protected Mode mode;
  protected static final String CPU_LIGHT = "cpuLight";
  protected static final String IO = "I/O";
  protected static final String CPU_INTENSIVE = "cpuIntensive";
  protected static final String CUSTOM = "custom";
  protected static final String RING_BUFFER = "ringBuffer";
  protected static final String EXECUTOR = "executor";
  protected static final int STREAM_ITERATIONS = 5000;

  protected Supplier<Builder> flowBuilder;
  protected Flow flow;
  protected Set<String> threads = synchronizedSet(new HashSet<>());
  protected Set<String> schedulers = synchronizedSet(new HashSet<>());
  protected TriggerableMessageSource triggerableMessageSource = getTriggerableMessageSource();
  protected Processor cpuLightProcessor = new ThreadTrackingProcessor() {

    @Override
    public ProcessingType getProcessingType() {
      return CPU_LITE;
    }
  };
  protected Processor cpuIntensiveProcessor = new ThreadTrackingProcessor() {

    @Override
    public ProcessingType getProcessingType() {
      return ProcessingType.CPU_INTENSIVE;
    }
  };
  protected Processor blockingProcessor = new ThreadTrackingProcessor() {

    @Override
    public ProcessingType getProcessingType() {
      return BLOCKING;
    }
  };
  protected Processor asyncProcessor = new ThreadTrackingProcessor() {

    @Override
    public ProcessingType getProcessingType() {
      return CPU_LITE_ASYNC;
    }
  };
  protected Processor cpuLightInnerPublisherProcessor;
  protected Processor cpuIntensiveInnerPublisherProcessor;
  protected Processor blockingInnerPublisherProcessor;
  protected Processor asyncInnerPublisherProcessor;
  protected Processor annotatedAsyncProcessor = new AnnotatedAsyncProcessor();

  protected Processor failingProcessor = new ThreadTrackingProcessor() {

    @Override
    public CoreEvent process(CoreEvent event) {
      throw new RuntimeException("FAILURE");
    }
  };

  protected Processor errorSuccessProcessor = new ThreadTrackingProcessor() {

    private final AtomicInteger count = new AtomicInteger();

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      if (count.getAndIncrement() % 10 < 5) {
        return super.process(event);
      } else {
        return failingProcessor.process(event);
      }
    }
  };

  protected Processor ioRWProcessor = new ThreadTrackingProcessor() {

    @Override
    public ProcessingType getProcessingType() {
      return IO_RW;
    }
  };

  protected final ProfilingService profilingService = new DefaultProfilingService() {

    @Override
    public ProfilingDataConsumerDiscoveryStrategy getDiscoveryStrategy() {
      return () -> singleton(profilingDataConsumer);
    }

    @Override
    public EventTracer<CoreEvent> getCoreEventTracer() {
      return getNoopCoreEventTracer();
    }
  };

  protected ProcessingStrategy ps;
  protected CountDownLatch innerPublisherLatch;

  protected TestScheduler cpuLight;
  protected TestScheduler blocking;
  protected TestScheduler cpuIntensive;
  protected TestScheduler customWrapped;
  protected Scheduler custom;
  protected TestScheduler ringBuffer;
  protected TestScheduler asyncExecutor;
  protected ExecutorService cachedThreadPool = newFixedThreadPool(CORES, new NamedThreadFactory("cachedThreadPool"));

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public SystemProperty enableProfilingServiceProperty;

  public AbstractProcessingStrategyTestCase(Mode mode, boolean profiling) {
    this.mode = mode;
    this.enableProfilingServiceProperty =
        new SystemProperty((ENABLE_PROFILING_SERVICE.getOverridingSystemPropertyName().get()),
                           Boolean.toString(profiling));
    this.profiling = profiling;
  }

  @Parameterized.Parameters(name = "{0} - Profiling: {1}")
  public static List<Object[]> modeParameters() {
    return asList(new Object[] {FLOW, false},
                  new Object[] {FLOW, true},
                  new Object[] {SOURCE, false});
  }

  @Before
  public void before() throws RegistrationException {
    ps = createProcessingStrategy(muleContext, "test");
    innerPublisherLatch = new CountDownLatch(1);

    cpuLightInnerPublisherProcessor = new WithInnerPublisherProcessor(ps, innerPublisherLatch) {

      @Override
      public ProcessingType getProcessingType() {
        return CPU_LITE;
      }
    };
    cpuIntensiveInnerPublisherProcessor = new WithInnerPublisherProcessor(ps, innerPublisherLatch) {

      @Override
      public ProcessingType getProcessingType() {
        return ProcessingType.CPU_INTENSIVE;
      }
    };
    blockingInnerPublisherProcessor = new WithInnerPublisherProcessor(ps, innerPublisherLatch) {

      @Override
      public ProcessingType getProcessingType() {
        return BLOCKING;
      }
    };
    asyncInnerPublisherProcessor = new WithInnerPublisherProcessor(ps, innerPublisherLatch) {

      @Override
      public ProcessingType getProcessingType() {
        return CPU_LITE_ASYNC;
      }
    };

    cpuLight = new TestScheduler(2, CPU_LIGHT, false);
    blocking = new TestScheduler(4, IO, true);
    cpuIntensive = new TestScheduler(2, CPU_INTENSIVE, true);
    customWrapped = new TestScheduler(4, CUSTOM, true);
    custom = new RetrySchedulerWrapper(customWrapped, 5, () -> {
    });
    ringBuffer = new TestScheduler(1, RING_BUFFER, true);
    asyncExecutor = new TestScheduler(CORES, EXECUTOR, false);

    flowBuilder = () -> builder("test", muleContext)
        .processingStrategyFactory(createProcessingStrategyFactory())
        .source(triggerableMessageSource)
        // Avoid logging of errors by using a null exception handler.
        .messagingExceptionHandler((exception, event) -> event);

    // Profiling events mocking
    when(profilingDataConsumer.getProfilingEventTypes())
        .thenReturn(new HashSet<>(Arrays.asList(PS_SCHEDULING_FLOW_EXECUTION, STARTING_FLOW_EXECUTION, FLOW_EXECUTED,
                                                PS_SCHEDULING_OPERATION_EXECUTION, PS_STARTING_OPERATION_EXECUTION,
                                                PS_OPERATION_EXECUTED, PS_FLOW_MESSAGE_PASSING)));
    when(profilingDataConsumer.getEventContextFilter()).thenReturn(processingStrategyProfilingEventContext -> true);

    setFeaturesState(profiling);
  }

  private void setFeaturesState(boolean state) throws RegistrationException {
    ProfilingFeatureFlaggingService featureFlaggingService =
        ((MuleContextWithRegistry) muleContext).getRegistry().lookupObject(ProfilingFeatureFlaggingService.class);
    featureFlaggingService
        .toggleProfilingFeature(PS_SCHEDULING_FLOW_EXECUTION, profilingDataConsumer.getClass().getName(), state);
    featureFlaggingService.toggleProfilingFeature(STARTING_FLOW_EXECUTION, profilingDataConsumer.getClass().getName(), state);
    featureFlaggingService.toggleProfilingFeature(FLOW_EXECUTED, profilingDataConsumer.getClass().getName(), state);
    featureFlaggingService
        .toggleProfilingFeature(PS_SCHEDULING_OPERATION_EXECUTION, profilingDataConsumer.getClass().getName(), state);
    featureFlaggingService
        .toggleProfilingFeature(PS_STARTING_OPERATION_EXECUTION, profilingDataConsumer.getClass().getName(), state);
    featureFlaggingService.toggleProfilingFeature(PS_OPERATION_EXECUTED, profilingDataConsumer.getClass().getName(), state);
    featureFlaggingService.toggleProfilingFeature(PS_FLOW_MESSAGE_PASSING, profilingDataConsumer.getClass().getName(), state);
  }

  @Override
  protected InternalEvent.Builder getEventBuilder() throws MuleException {
    return InternalEvent.builder(create(flow, TEST_CONNECTOR_LOCATION));
  }

  protected ProcessingStrategyFactory createProcessingStrategyFactory() {
    return (muleContext, prefix) -> ps;
  }

  protected abstract ProcessingStrategy createProcessingStrategy(MuleContext muleContext, String schedulersNamePrefix);

  @After
  public void after() throws MuleException {
    if (flow != null) {
      flow.stop();
      flow.dispose();
    }
    ringBuffer.stop();
    cpuLight.stop();
    blocking.stop();
    cpuIntensive.stop();
    custom.stop();
    asyncExecutor.stop();

    ringBuffer.assertNoFailures();
    cpuLight.assertNoFailures();
    blocking.assertNoFailures();
    cpuIntensive.assertNoFailures();
    customWrapped.assertNoFailures();
    asyncExecutor.assertNoFailures();

    cachedThreadPool.shutdownNow();
    setFeaturesState(false);
  }

  @Test
  public void singleCpuLight() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor).build();

    startFlow();
    processFlow(testEvent());

    assertThat(schedulers.toString(), schedulers, cpuLightSchedulerMatcher());
  }

  protected Matcher<Iterable<? extends String>> cpuLightSchedulerMatcher() {
    return contains(CPU_LIGHT);
  }

  @Test
  public void singleCpuLightConcurrent() throws Exception {
    internalConcurrent(flowBuilder.get(), false, CPU_LITE, 1);
  }

  @Test
  public void singleBlockingConcurrent() throws Exception {
    internalConcurrent(flowBuilder.get(), false, BLOCKING, 1);
  }

  protected void internalConcurrent(Builder flowBuilder, boolean blocks, ProcessingType processingType, int invocations,
                                    Processor... processorsBeforeLatch)
      throws MuleException, InterruptedException {
    MultipleInvocationLatchedProcessor latchedProcessor =
        new MultipleInvocationLatchedProcessor(processingType, invocations);

    List<Processor> processors = new ArrayList<>(asList(processorsBeforeLatch));
    processors.add(latchedProcessor);
    flow = flowBuilder.processors(processors).build();
    startFlow();

    for (int i = 0; i < invocations; i++) {
      asyncExecutor.submit(() -> processFlow(newEvent()));
    }

    assertThat("Processors not executed in time",
               latchedProcessor.getAllLatchedLatch().await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));

    asyncExecutor.submit(() -> processFlow(newEvent()));

    assertThat(latchedProcessor.getUnlatchedInvocationLatch().await(BLOCK_TIMEOUT, MILLISECONDS), is(!blocks));

    // We need to assert the threads logged at this point. But good idea to ensure once unlocked the pending invocation completes.
    // To do this need to copy threads locally.
    Set<String> threadsBeforeUnlock = new HashSet<>(threads);
    Set<String> schedulersBeforeUnlock = new HashSet<>(schedulers);

    latchedProcessor.release();

    if (blocks) {
      assertThat(latchedProcessor.getUnlatchedInvocationLatch().await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
    }

    threads = threadsBeforeUnlock;
    schedulers = schedulersBeforeUnlock;
  }

  @Test
  public void multipleCpuLight() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor, cpuLightProcessor, cpuLightProcessor).build();
    startFlow();

    processFlow(testEvent());
  }

  @Test
  public void singleBlocking() throws Exception {
    flow = flowBuilder.get().processors(blockingProcessor).build();
    startFlow();

    processFlow(testEvent());

    assertThat(schedulers.toString(), schedulers, ioSchedulerMatcher());
  }

  @Test
  public void singleBlockingInnerPublisher() throws Exception {
    flow = flowBuilder.get().processors(blockingInnerPublisherProcessor).build();
    startFlow();

    cpuLight.execute(() -> {
      try {
        processFlow(testEvent());
      } catch (Exception e) {
        throw new MuleRuntimeException(e);
      }
    });

    // Wait for the flow to be processing
    Thread.sleep(100);

    // simulate an inner publisher still processing events when the flow is stopped.
    flow.stop();
    innerPublisherLatch.countDown();

    assertThat(schedulers.toString(), schedulers, ioSchedulerMatcher());
    flow.dispose();
    flow = null;
  }

  protected Matcher<Iterable<? extends String>> ioSchedulerMatcher() {
    return contains(IO);
  }

  @Test
  public void multipleBlocking() throws Exception {
    flow = flowBuilder.get().processors(blockingProcessor, blockingProcessor, blockingProcessor).build();
    startFlow();

    processFlow(testEvent());
  }

  @Test
  public void singleCpuIntensive() throws Exception {
    flow = flowBuilder.get().processors(cpuIntensiveProcessor).build();
    startFlow();

    processFlow(testEvent());

    assertThat(schedulers.toString(), schedulers, cpuIntensiveSchedulerMatcher());
  }

  protected Matcher<Iterable<? extends String>> cpuIntensiveSchedulerMatcher() {
    return contains(CPU_INTENSIVE);
  }

  @Test
  public void multipleCpuIntensive() throws Exception {
    flow =
        flowBuilder.get().processors(cpuIntensiveProcessor, cpuIntensiveProcessor, cpuIntensiveProcessor).build();
    startFlow();

    processFlow(testEvent());
  }

  @Test
  public void mix() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor, cpuIntensiveProcessor, blockingProcessor).build();
    startFlow();

    processFlow(testEvent());
  }

  @Test
  public void mix2() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor, cpuLightProcessor, blockingProcessor, blockingProcessor,
                                        cpuLightProcessor, cpuIntensiveProcessor, cpuIntensiveProcessor,
                                        cpuLightProcessor)
        .build();
    startFlow();

    processFlow(testEvent());
  }

  @Test
  public void asyncCpuLight() throws Exception {
    flow = flowBuilder.get().processors(asyncProcessor, cpuLightProcessor).build();
    startFlow();

    processFlow(testEvent());
  }

  @Test
  public void asyncCpuLightConcurrent() throws Exception {
    internalConcurrent(flowBuilder.get(), false, CPU_LITE, 1, asyncProcessor);
  }

  @Test
  public void stream() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor).build();
    startFlow();

    CountDownLatch latch = new CountDownLatch(STREAM_ITERATIONS);
    for (int i = 0; i < STREAM_ITERATIONS; i++) {
      dispatchFlow(newEvent(), t -> latch.countDown(), response -> bubble(new AssertionError("Unexpected error")));
    }
    assertThat(latch.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
  }

  @Test
  public void streamAfterRestart() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor).build();
    startFlow();

    CountDownLatch latch = new CountDownLatch(STREAM_ITERATIONS);
    for (int i = 0; i < STREAM_ITERATIONS; i++) {
      dispatchFlow(newEvent(), t -> latch.countDown(), response -> bubble(new AssertionError("Unexpected error")));
    }
    assertThat(latch.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));

    flow.stop();
    cpuLight.stop();
    blocking.stop();
    cpuIntensive.stop();
    ringBuffer.stop();

    cpuLight.assertNoFailures();
    blocking.assertNoFailures();
    cpuIntensive.assertNoFailures();
    ringBuffer.assertNoFailures();

    cpuLight = new TestScheduler(2, CPU_LIGHT, false);
    blocking = new TestScheduler(4, IO, true);
    cpuIntensive = new TestScheduler(2, CPU_INTENSIVE, true);
    ringBuffer = new TestScheduler(1, RING_BUFFER, true);
    flow.start();

    CountDownLatch latchAfter = new CountDownLatch(STREAM_ITERATIONS);
    for (int i = 0; i < STREAM_ITERATIONS; i++) {
      dispatchFlow(newEvent(), t -> latchAfter.countDown(), response -> bubble(new AssertionError("Unexpected error")));
    }
    assertThat(latchAfter.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
  }

  @Test
  public void concurrentStream() throws Exception {
    flow = flowBuilder.get().processors(cpuLightProcessor).build();
    startFlow();

    CountDownLatch latch = new CountDownLatch(STREAM_ITERATIONS);
    for (int i = 0; i < CONCURRENT_TEST_CONCURRENCY; i++) {
      asyncExecutor.submit(() -> {
        for (int j = 0; j < STREAM_ITERATIONS / CONCURRENT_TEST_CONCURRENCY; j++) {
          try {
            dispatchFlow(newEvent(), t -> latch.countDown(),
                         response -> bubble(new AssertionError("Unexpected error")));
          } catch (MuleException e) {
            throw new RuntimeException(e);
          }
        }
      });
    }
    assertThat(latch.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
  }

  @Test
  public void errorsStream() throws Exception {
    flow = flowBuilder.get().processors(failingProcessor).build();
    startFlow();

    CountDownLatch latch = new CountDownLatch(STREAM_ITERATIONS);
    for (int i = 0; i < STREAM_ITERATIONS; i++) {
      dispatchFlow(newEvent(), response -> bubble(new AssertionError("Unexpected success")), t -> latch.countDown());
    }
    assertThat(latch.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
  }

  @Test
  public void errorSuccessStream() throws Exception {
    flow = flowBuilder.get().processors(errorSuccessProcessor).build();
    startFlow();

    CountDownLatch sucessLatch = new CountDownLatch(STREAM_ITERATIONS / 2);
    CountDownLatch errorLatch = new CountDownLatch(STREAM_ITERATIONS / 2);
    for (int i = 0; i < STREAM_ITERATIONS; i++) {
      dispatchFlow(newEvent(), response -> sucessLatch.countDown(), t -> errorLatch.countDown());
    }
    assertThat(sucessLatch.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
    assertThat(errorLatch.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
  }

  @Test
  public abstract void tx() throws Exception;

  @Test
  public void txSameThreadPolicyHonored() throws Exception {
    assumeThat(this, instanceOf(TransactionAwareProcessingStrategyTestCase.class));

    triggerableMessageSource = new TriggerableMessageSource();

    flow = flowBuilder.get()
        .source(triggerableMessageSource)
        .processors(cpuLightProcessor, cpuIntensiveProcessor, blockingProcessor).build();
    startFlow();

    getInstance()
        .bindTransaction(new TestTransaction("appName", getNotificationDispatcher(muleContext)));
    processFlow(newEvent());

    assertThat(threads.toString(), threads, hasSize(equalTo(1)));
    assertThat(threads.toString(), threads, hasItem(currentThread().getName()));
  }

  @Test
  public void txSameThreadPolicyHonoredWithAsyncProcessorInFlow() throws Exception {
    assumeThat(this, instanceOf(TransactionAwareProcessingStrategyTestCase.class));

    triggerableMessageSource = new TriggerableMessageSource();

    flow = flowBuilder.get()
        .source(triggerableMessageSource)
        .processors(asyncProcessor, cpuLightProcessor, cpuIntensiveProcessor, blockingProcessor).build();
    startFlow();

    getInstance()
        .bindTransaction(new TestTransaction("appName", getNotificationDispatcher(muleContext)));
    processFlow(newEvent());

    assertThat(threads.toString(), threads, hasSize(equalTo(1)));
    assertThat(threads.toString(), threads, hasItem(currentThread().getName()));
  }

  protected void assertProcessingStrategyProfiling() {
    if (parseBoolean(enableProfilingServiceProperty.getValue())) {
      InOrder profilingDataConsumerAssertions = inOrder(profilingDataConsumer);
      profilingDataConsumerAssertions.verify(profilingDataConsumer, times(1))
          .onProfilingEvent(eq(STARTING_FLOW_EXECUTION), any(ComponentProcessingStrategyProfilingEventContext.class));
      profilingDataConsumerAssertions.verify(profilingDataConsumer, times(1))
          .onProfilingEvent(eq(PS_SCHEDULING_OPERATION_EXECUTION), any(ComponentProcessingStrategyProfilingEventContext.class));
      profilingDataConsumerAssertions.verify(profilingDataConsumer, times(1))
          .onProfilingEvent(eq(PS_STARTING_OPERATION_EXECUTION), any(ComponentProcessingStrategyProfilingEventContext.class));
      profilingDataConsumerAssertions.verify(profilingDataConsumer, times(1))
          .onProfilingEvent(eq(PS_OPERATION_EXECUTED), any(ComponentProcessingStrategyProfilingEventContext.class));
      profilingDataConsumerAssertions.verify(profilingDataConsumer, times(1))
          .onProfilingEvent(eq(PS_FLOW_MESSAGE_PASSING), any(ComponentProcessingStrategyProfilingEventContext.class));
      profilingDataConsumerAssertions.verify(profilingDataConsumer, times(1))
          .onProfilingEvent(eq(FLOW_EXECUTED), any(ComponentProcessingStrategyProfilingEventContext.class));
    } else {
      verify(profilingDataConsumer, never()).onProfilingEvent(any(), any());
    }
  }

  protected void assertProcessingStrategyTracing() {
    Assert.assertThat(profilingService.getTracingService().getCurrentExecutionContext(), notNullValue());
  }

  protected interface TransactionAwareProcessingStrategyTestCase {

  }

  protected void singleIORW(Callable<CoreEvent> eventSupplier, Matcher<Iterable<? extends String>> schedulerNameMatcher)
      throws Exception {
    flow = flowBuilder.get().processors(ioRWProcessor).build();

    startFlow();
    processFlow(eventSupplier.call());
    assertThat(schedulers.toString(), schedulers, schedulerNameMatcher);
  }

  protected CoreEvent processFlow(CoreEvent event) throws Exception {
    setMuleContextIfNeeded(flow, muleContext);
    switch (mode) {
      case FLOW:
        return flow.process(event);
      case SOURCE:
        try {
          return just(event)
              .doOnNext(flow::checkBackpressure)
              .onErrorMap(FlowBackPressureException.class,
                          backPressureExceptionMapper())
              .transform(triggerableMessageSource.getListener())
              .block();
        } catch (Throwable throwable) {
          throw rxExceptionToMuleException(throwable);
        }
      default:
        return null;
    }
  }

  private Function<FlowBackPressureException, Throwable> backPressureExceptionMapper() {
    return backpressureException -> {
      try {
        return new MessagingException(newEvent(), backpressureException, backpressureException.getFlow());
      } catch (MuleException e) {
        throw propagate(e);
      }
    };
  }

  protected void dispatchFlow(CoreEvent event, Consumer<CoreEvent> onSuccess,
                              Consumer<Throwable> onError) {
    setMuleContextIfNeeded(flow, muleContext);
    switch (mode) {
      case FLOW:
        ((BaseEventContext) event.getContext()).onResponse((response, throwable) -> {
          onSuccess.accept(response);
          onError.accept(throwable);
        });
        just(event).transform(flow).subscribe(requestUnbounded());
        break;
      case SOURCE:
        ((BaseEventContext) event.getContext()).onResponse((response, throwable) -> {
          onSuccess.accept(response);
          onError.accept(throwable);
        });
        just(event).transform(triggerableMessageSource.getListener()).subscribe(requestUnbounded());
    }
  }

  protected void testAsyncCpuLightNotificationThreads(AtomicReference<Thread> beforeThread, AtomicReference<Thread> afterThread)
      throws Exception {
    muleContext.getNotificationManager().addInterfaceToType(MessageProcessorNotificationListener.class,
                                                            MessageProcessorNotification.class);
    muleContext.getNotificationManager().addListener((MessageProcessorNotificationListener) notification -> {
      if (new IntegerAction(MESSAGE_PROCESSOR_PRE_INVOKE).equals(notification.getAction())) {
        beforeThread.set(currentThread());
      } else if (new IntegerAction(MESSAGE_PROCESSOR_POST_INVOKE).equals(notification.getAction())) {
        afterThread.set(currentThread());
      }
    });
    flow = flowBuilder.get().processors(annotatedAsyncProcessor).build();
    startFlow();
    processFlow(testEvent());
  }

  protected void testBackPressure(BackPressureStrategy backPressureStrategy, Matcher<Integer> processedAssertion,
                                  Matcher<Integer> rejectedAssertion, Matcher<Integer> totalAssertion)
      throws MuleException {
    if (mode.equals(SOURCE)) {
      Latch latch = new Latch();
      triggerableMessageSource = new TriggerableMessageSource(backPressureStrategy);
      flow =
          flowBuilder.get()
              .source(triggerableMessageSource)
              .processors(asList(cpuLightProcessor, new ThreadTrackingProcessor() {

                @Override
                public CoreEvent process(CoreEvent event) throws MuleException {
                  try {
                    latch.await();
                  } catch (InterruptedException e) {
                    currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                  return super.process(event);
                }

                @Override
                public ProcessingType getProcessingType() {
                  return BLOCKING;
                }

              }))
              .maxConcurrency(2)
              .build();
      startFlow();

      AtomicInteger rejected = new AtomicInteger();
      AtomicInteger processed = new AtomicInteger();

      for (int i = 0; i < STREAM_ITERATIONS; i++) {
        cachedThreadPool.submit(() -> Flux.just(newEvent())
            .cast(CoreEvent.class)
            .doOnNext(flow::checkBackpressure)
            .onErrorMap(FlowBackPressureException.class,
                        backPressureExceptionMapper())
            .transform(triggerableMessageSource.getListener())
            .doOnNext(event -> processed.getAndIncrement())
            .doOnError(MessagingException.class, e -> {
              assertThat(e.getFailingComponent(), is(flow));
              rejected.getAndIncrement();
            })
            .subscribe(event -> {
            }, t -> {
              if (!(t instanceof MessagingException)) {
                t.printStackTrace();
              }
            }));
        if (i == STREAM_ITERATIONS / 2) {
          latch.release();
        }
      }

      new PollingProber(DEFAULT_TIMEOUT * 10, DEFAULT_POLLING_INTERVAL)
          .check(new JUnitLambdaProbe(() -> {
            LOGGER.debug("DONE " + processed.get() + " , REJECTED " + rejected.get() + ", ");

            assertThat("total", rejected.get() + processed.get(), totalAssertion);
            assertThat("processed", processed.get(), processedAssertion);
            assertThat("rejected", rejected.get(), rejectedAssertion);

            return true;
          }));
    }
  }

  protected void startFlow() throws InitialisationException, MuleException {
    flow.setAnnotations(ImmutableMap.of(LOCATION_KEY, from("flow"), ANNOTATION_NAME,
                                        ComponentIdentifier.buildFromStringRepresentation("mule:flow")));
    flow.initialise();
    flow.start();
  }

  class MultipleInvocationLatchedProcessor extends AbstractComponent implements Processor {

    private final ProcessingType type;
    private volatile Latch latch = new Latch();
    private volatile CountDownLatch allLatchedLatch;
    private volatile Latch unlatchedInvocationLatch;
    private final AtomicInteger invocations;

    public MultipleInvocationLatchedProcessor(ProcessingType type, int latchedInvocations) {
      this.type = type;
      allLatchedLatch = new CountDownLatch(latchedInvocations);
      unlatchedInvocationLatch = new Latch();
      invocations = new AtomicInteger(latchedInvocations);
    }

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      threads.add(currentThread().getName());
      if (invocations.getAndDecrement() > 0) {
        allLatchedLatch.countDown();
        try {
          latch.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      } else {
        unlatchedInvocationLatch.countDown();
      }
      return event;
    }

    @Override
    public ProcessingType getProcessingType() {
      return type;
    }

    public void release() {
      latch.release();
    }

    public CountDownLatch getAllLatchedLatch() throws InterruptedException {
      return allLatchedLatch;
    }

    public Latch getUnlatchedInvocationLatch() throws InterruptedException {
      return unlatchedInvocationLatch;
    }

    @Override
    public ComponentIdentifier getIdentifier() {
      return ComponentIdentifier.buildFromStringRepresentation("mule:multiple-invocation-latched-processor");
    }

  }

  static class TestScheduler extends ScheduledThreadPoolExecutor implements Scheduler {

    private final List<AssertionError> failures = new ArrayList<>();

    private final String threadNamePrefix;
    private final ExecutorService executor;

    public TestScheduler(int threads, String threadNamePrefix, boolean reject) {
      super(1, new NamedThreadFactory(threadNamePrefix + ".tasks"));
      this.threadNamePrefix = threadNamePrefix;
      executor = new ThreadPoolExecutor(threads, threads, 0l, TimeUnit.MILLISECONDS,
                                        new LinkedBlockingQueue(reject ? threads : Integer.MAX_VALUE),
                                        new NamedThreadFactory(threadNamePrefix));
    }

    @Override
    public Future<?> submit(Runnable task) {
      return executor.submit(task);
    }

    @Override
    public Future<?> submit(Callable task) {
      return executor.submit(task);
    }

    @Override
    public void stop() {
      if (currentThread().getName().startsWith(threadNamePrefix)) {
        failures.add(new AssertionError("A scheduler must not be stopped from within itself"));
      }

      shutdown();
      executor.shutdown();
    }

    @Override
    public ScheduledFuture<?> scheduleWithCronExpression(Runnable command, String cronExpression) {
      throw new UnsupportedOperationException(
                                              "Cron expression scheduling is not supported in unit tests. You need the productive service implementation.");
    }

    @Override
    public ScheduledFuture<?> scheduleWithCronExpression(Runnable command, String cronExpression, TimeZone timeZone) {
      throw new UnsupportedOperationException(
                                              "Cron expression scheduling is not supported in unit tests. You need the productive service implementation.");
    }

    @Override
    public String getName() {
      return threadNamePrefix;
    }

    public void assertNoFailures() {
      if (!failures.isEmpty()) {
        throw failures.iterator().next();
      }
    }

    public void assertStopped() {
      if (!isShutdown()) {
        fail("Scheduler not stopped");
      }
    }
  }

  /**
   * Scheduler that rejects tasks {@link #REJECTION_COUNT} times and then delegates to delegate scheduler.
   */
  static class RejectingScheduler extends TestScheduler {

    static int REJECTION_COUNT = 10;
    private final AtomicInteger rejections = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final Scheduler delegate;

    public RejectingScheduler(Scheduler delegate) {
      super(1, "prefix", true);
      this.delegate = delegate;
    }

    @Override
    public Future<?> submit(Runnable task) {
      if (rejections.getAndUpdate(r -> r < REJECTION_COUNT ? r + 1 : r) < REJECTION_COUNT) {
        throw new RejectedExecutionException();
      } else {
        accepted.incrementAndGet();
        return delegate.submit(task);
      }
    }

    @Override
    public Future<?> submit(Callable task) {
      if (rejections.getAndUpdate(r -> r < REJECTION_COUNT ? r + 1 : r) < REJECTION_COUNT) {
        throw new RejectedExecutionException();
      } else {
        accepted.incrementAndGet();
        return delegate.submit(task);
      }
    }

    public int getRejections() {
      return rejections.get();
    }

    public int getAccepted() {
      return accepted.get();
    }

    public void reset() {
      rejections.set(0);
      accepted.set(0);
    }
  }

  class ThreadTrackingProcessor implements Processor, InternalProcessor {

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      threads.add(currentThread().getName());
      return event;
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      Flux<CoreEvent> schedulerTrackingPublisher = from(publisher)
          .doOnEach(signal -> signal.getContext().getOrEmpty(PROCESSOR_SCHEDULER_CONTEXT_KEY)
              .ifPresent(sch -> schedulers.add(((Scheduler) sch).getName())));

      if (getProcessingType() == CPU_LITE_ASYNC) {
        return from(schedulerTrackingPublisher).transform(processorPublisher -> Processor.super.apply(schedulerTrackingPublisher))
            .publishOn(fromExecutorService(custom)).onErrorStop();
      } else {
        return Processor.super.apply(schedulerTrackingPublisher);
      }
    }

    @Override
    public ComponentIdentifier getIdentifier() {
      return ComponentIdentifier.buildFromStringRepresentation("mule:thread-tracking-processor");
    }

  }

  class WithInnerPublisherProcessor extends ThreadTrackingProcessor {

    private final ProcessingStrategy ps;
    private final CountDownLatch latch;

    public WithInnerPublisherProcessor(ProcessingStrategy ps, CountDownLatch latch) {
      this.ps = ps;
      this.latch = latch;
    }

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      try {
        return super.process(event);
      } finally {
        try {
          latch.await();
        } catch (InterruptedException e) {
          currentThread().interrupt();
          throw new MuleRuntimeException(e);
        }
      }
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      return ps.configureInternalPublisher(super.apply(publisher));
    }

  }

  public static Matcher<Integer> between(int min, int max) {
    return allOf(greaterThanOrEqualTo(min), lessThanOrEqualTo(max));
  }

  public static Matcher<Long> between(long min, long max) {
    return allOf(greaterThanOrEqualTo(min), lessThanOrEqualTo(max));
  }

  protected void expectRejected(Class<? extends FlowBackPressureException> backpressureExceptionClass) {
    expectedException.expect(MessagingException.class);
    expectedException.expect(overloadErrorTypeMatcher());
    expectedException.expectCause(instanceOf(backpressureExceptionClass));
  }

  private Matcher<MessagingException> overloadErrorTypeMatcher() {
    return new TypeSafeMatcher<MessagingException>() {

      private String errorTypeId;

      @Override
      public void describeTo(org.hamcrest.Description description) {
        description.appendValue(errorTypeId);
      }

      @Override
      protected boolean matchesSafely(MessagingException item) {
        errorTypeId = item.getEvent().getError().get().getErrorType().getIdentifier();
        return FLOW_BACK_PRESSURE_ERROR_IDENTIFIER.equals(errorTypeId);
      }

      @Override
      protected void describeMismatchSafely(MessagingException item, Description mismatchDescription) {
        mismatchDescription.appendValue(item.getEvent().getError().get().getErrorType().getIdentifier());
      }
    };
  }

  class AnnotatedAsyncProcessor extends AbstractComponent implements AnnotatedProcessor {

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      return asyncProcessor.process(event);
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      return asyncProcessor.apply(publisher);
    }

    @Override
    public ComponentLocation getLocation() {
      return TEST_CONNECTOR_LOCATION;
    }

    @Override
    public ProcessingType getProcessingType() {
      return asyncProcessor.getProcessingType();
    }

    @Override
    public ComponentIdentifier getIdentifier() {
      return ComponentIdentifier.buildFromStringRepresentation("mule:annotated-async-processor");
    }
  }

  public enum Mode {
    /**
     * Test using {@link Flow#process(CoreEvent)}.
     */
    FLOW,
    /**
     * Test using {@link org.mule.runtime.core.api.source.MessageSource}
     */
    SOURCE
  }

  @Override
  protected boolean isGracefulShutdown() {
    return true;
  }

}
