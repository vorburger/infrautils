/*
 * Copyright (c) 2017 - 2018 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.metrics.internal;

import static com.codahale.metrics.Slf4jReporter.LoggingLevel.INFO;
import static java.lang.management.ManagementFactory.getThreadMXBean;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.jmx.JmxReporter;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.CachedThreadStatesGaugeSet;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadDeadlockDetector;
import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import org.apache.aries.blueprint.annotation.service.Service;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.infrautils.metrics.Counter;
import org.opendaylight.infrautils.metrics.Labeled;
import org.opendaylight.infrautils.metrics.Meter;
import org.opendaylight.infrautils.metrics.MetricDescriptor;
import org.opendaylight.infrautils.metrics.MetricProvider;
import org.opendaylight.infrautils.metrics.Timer;
import org.opendaylight.infrautils.utils.UncheckedCloseable;
import org.opendaylight.infrautils.utils.function.CheckedCallable;
import org.opendaylight.infrautils.utils.function.CheckedRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link MetricProvider} based on <a href="http://metrics.dropwizard.io">Coda Hale's Dropwizard Metrics</a>.
 *
 * @author Michael Vorburger.ch
 */
@Singleton
@Service(classes = MetricProvider.class)
public class MetricProviderImpl implements MetricProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MetricProviderImpl.class);

    private final Map<String, MeterImpl> meters = new ConcurrentHashMap<>();
    private final Map<String, CounterImpl> counters = new ConcurrentHashMap<>();
    private final Map<String, TimerImpl> timers = new ConcurrentHashMap<>();
    private final MetricRegistry registry;
    private final JmxReporter jmxReporter;
    private final Slf4jReporter slf4jReporter;

    private volatile @Nullable MetricsFileReporter fileReporter;
    private volatile @Nullable ThreadsWatcher threadsWatcher;

    public MetricProviderImpl() {
        this.registry = new MetricRegistry();

        setUpJvmMetrics(registry);

        jmxReporter = setUpJmxReporter(registry);

        slf4jReporter = setUpSlf4jReporter(registry);

        // TODO really get this to work in Karaf, through PAX Logging.. (it's currently NOK)
        // instrumentLog4jV2(registry);
    }

    public MetricProviderImpl(Configuration configuration) {
        this();
        updateConfiguration(configuration);
    }

    public final void updateConfiguration(Configuration configuration) {
        if (threadsWatcher != null) {
            threadsWatcher.close();
        }
        if (configuration.getThreadsWatcherIntervalMS() > 0 && (threadsWatcher == null
                || configuration.getThreadsWatcherIntervalMS() != threadsWatcher.getInterval().toMillis()
                || configuration.getMaxThreads() != threadsWatcher.getMaxThreads())
                || configuration.getMaxThreadsMaxLogIntervalSecs()
                        != threadsWatcher.getMaxThreadsMaxLogInterval().getSeconds()
                || configuration.getDeadlockedThreadsMaxLogIntervalSecs()
                        != threadsWatcher.getDeadlockedThreadsMaxLogInterval().getSeconds()) {
            threadsWatcher = new ThreadsWatcher(configuration.getMaxThreads(),
                    Duration.ofMillis(configuration.getThreadsWatcherIntervalMS()),
                    Duration.ofSeconds(configuration.getMaxThreadsMaxLogIntervalSecs()),
                    Duration.ofSeconds(configuration.getDeadlockedThreadsMaxLogIntervalSecs()));
            threadsWatcher.start();
        }

        if (fileReporter != null) {
            fileReporter.close();
        }
        int fileReporterInterval = fileReporter != null ? (int)fileReporter.getInterval().getSeconds() : 0;
        if (fileReporterInterval != configuration.getFileReporterIntervalSecs()) {
            if (configuration.getFileReporterIntervalSecs() > 0) {
                fileReporter = new MetricsFileReporter(registry,
                        Duration.ofSeconds(configuration.getFileReporterIntervalSecs()));
                fileReporter.startReporter();
            }
        }
        LOG.info("Updated: {}", configuration);
    }

    @PreDestroy
    public void close() {
        jmxReporter.close();
        if (fileReporter != null) {
            fileReporter.close();
        }
        slf4jReporter.close();
        if (threadsWatcher != null) {
            threadsWatcher.close();
        }
    }

    @VisibleForTesting
    public MetricRegistry getRegistry() {
        return registry;
    }

    private static void setUpJvmMetrics(MetricRegistry registry) {
        ThreadDeadlockDetector threadDeadlockDetector = new ThreadDeadlockDetector();
        FileDescriptorRatioGauge fileDescriptorRatioGauge = new FileDescriptorRatioGauge();

        registry.registerAll(new GarbageCollectorMetricSet());
        registry.registerAll(new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
        registry.registerAll(new CachedThreadStatesGaugeSet(getThreadMXBean(), threadDeadlockDetector, 13, SECONDS));
        registry.registerAll(new MemoryUsageGaugeSet());
        registry.registerAll(new ClassLoadingGaugeSet());
        registry.gauge("odl.infrautils.FileDescriptorRatio", () -> fileDescriptorRatioGauge);
    }

    private static JmxReporter setUpJmxReporter(MetricRegistry registry) {
        JmxReporter reporter = JmxReporter.forRegistry(registry)
                .createsObjectNamesWith(new CustomObjectNameFactory()).build();
        reporter.start();
        LOG.info("JmxReporter started, ODL application's metrics are now available via JMX");
        return reporter;
    }

    private static Slf4jReporter setUpSlf4jReporter(MetricRegistry registry) {
        Slf4jReporter slf4jReporter = Slf4jReporter.forRegistry(registry)
                .convertDurationsTo(MILLISECONDS)
                .convertRatesTo(SECONDS)
                .outputTo(LOG)
                .prefixedWith("JVM")
                .withLoggingLevel(INFO)
                .shutdownExecutorOnStop(true)
                .build();
        // NB: We do intentionally *NOT* start() the Slf4jReporter to log all metrics regularly;
        // as that will spam the log, and we have our own file based reporting instead.
        // We do log system metrics once at boot up:
        LOG.info("One time system JVM metrics FYI; "
                + "to watch continously, monitor via JMX or enable periodic file dump option");
        slf4jReporter.report();
        return slf4jReporter;
    }

    // http://metrics.dropwizard.io/3.1.0/manual/log4j/
//    private static void instrumentLog4jV2(MetricRegistry registry) {
//        // TODO Confirm that Level ALL is a good idea?
//        Level level = ALL;
//
//        InstrumentedAppender appender = new InstrumentedAppender(registry,
//                null /* null Filter fine, because we don't use filters */,
//                null /* null PatternLayout, because the layout isn't used in InstrumentedAppender */, false);
//        appender.start();
//
//        LoggerContext context = (LoggerContext) LogManager.getContext(false);
//        Configuration config = context.getConfiguration();
//        config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME).addAppender(appender, level,
//                null /* null Filter fine, because we don't use filters */);
//        context.updateLoggers(config);
//    }

    private org.opendaylight.infrautils.metrics.Meter newOrExistingMeter(Object anchor, String id) {
        return meters.computeIfAbsent(id, newId -> {
            LOG.debug("New Meter metric: {}", id);
            return new MeterImpl(newId);
        });
    }

    @Override
    public org.opendaylight.infrautils.metrics.Meter newMeter(Object anchor, String id) {
        requireNonNull(anchor, "anchor == null");
        checkForExistingID(id);
        return newOrExistingMeter(anchor, id);
    }

    @Override
    public Meter newMeter(MetricDescriptor descriptor) {
        return newMeter(descriptor.anchor(), makeCodahaleID(descriptor));
    }

    @Override
    public Labeled<Meter> newMeter(MetricDescriptor descriptor, String labelName) {
        return labelValue -> newOrExistingMeter(descriptor.anchor(),
                makeCodahaleID(descriptor) + "{" + labelName + "=" + labelValue + "}");
    }

    @Override
    public Labeled<Labeled<Meter>> newMeter(MetricDescriptor descriptor,
            String firstLabelName, String secondLabelName) {
        return firstLabelValue -> secondLabelValue -> newOrExistingMeter(descriptor.anchor(), makeCodahaleID(descriptor)
                + "{" + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue + "}");
    }

    @Override
    public Labeled<Labeled<Labeled<Meter>>> newMeter(MetricDescriptor descriptor,
            String firstLabelName, String secondLabelName, String thirdLabelName) {
        return firstLabelValue -> secondLabelValue -> thirdLabelValue ->
        newOrExistingMeter(descriptor.anchor(), makeCodahaleID(descriptor) + "{"
                + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue  + ","
                + thirdLabelName + "=" + thirdLabelValue + "}");
    }

    @Override
    public Labeled<Labeled<Labeled<Labeled<Meter>>>> newMeter(MetricDescriptor descriptor,
            String firstLabelName, String secondLabelName, String thirdLabelName, String fourthLabelName) {
        return firstLabelValue -> secondLabelValue -> thirdLabelValue -> fourthLabelValue ->
                newOrExistingMeter(descriptor.anchor(), makeCodahaleID(descriptor) + "{"
                        + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue  + ","
                        + thirdLabelName + "=" + thirdLabelValue + ","
                        + fourthLabelName + "=" + fourthLabelValue + "}");
    }

    @Override
    public Labeled<Labeled<Labeled<Labeled<Labeled<Meter>>>>> newMeter(MetricDescriptor descriptor,
            String firstLabelName, String secondLabelName, String thirdLabelName,
            String fourthLabelName, String fifthLabelName) {
        return firstLabelValue -> secondLabelValue -> thirdLabelValue -> fourthLabelValue -> fifthLabelValue ->
                newOrExistingMeter(descriptor.anchor(), makeCodahaleID(descriptor) + "{"
                        + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue  + ","
                        + thirdLabelName + "=" + thirdLabelValue + ","
                        + fourthLabelName + "=" + fourthLabelValue + ","
                        + fifthLabelName + "=" + fifthLabelValue + "}");
    }

    private org.opendaylight.infrautils.metrics.Counter newOrExistingCounter(Object anchor, String id) {
        return counters.computeIfAbsent(id, newId -> {
            LOG.debug("New Counter metric: {}", id);
            return new CounterImpl(newId);
        });
    }

    @Override
    public org.opendaylight.infrautils.metrics.Counter newCounter(Object anchor, String id) {
        requireNonNull(anchor, "anchor == null");
        checkForExistingID(id);
        return newOrExistingCounter(anchor, id);
    }

    @Override
    public Counter newCounter(MetricDescriptor descriptor) {
        return newCounter(descriptor.anchor(), makeCodahaleID(descriptor));
    }

    @Override
    public Labeled<Counter> newCounter(MetricDescriptor descriptor, String labelName) {
        return labelValue -> newOrExistingCounter(descriptor.anchor(),
                makeCodahaleID(descriptor) + "{" + labelName + "=" + labelValue + "}");
    }

    @Override
    public Labeled<Labeled<Counter>> newCounter(MetricDescriptor descriptor,
                                            String firstLabelName, String secondLabelName) {
        return firstLabelValue -> secondLabelValue -> newOrExistingCounter(descriptor.anchor(),
                makeCodahaleID(descriptor) + "{" + firstLabelName + "=" + firstLabelValue + ","
                    + secondLabelName + "=" + secondLabelValue + "}");
    }

    @Override
    public Labeled<Labeled<Labeled<Counter>>> newCounter(MetricDescriptor descriptor, String firstLabelName,
                                                         String secondLabelName, String thirdLabelName) {
        return firstLabelValue -> secondLabelValue -> thirdLabelValue ->
                newOrExistingCounter(descriptor.anchor(), makeCodahaleID(descriptor) + "{"
                        + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue  + ","
                        + thirdLabelName + "=" + thirdLabelValue + "}");
    }

    @Override
    public Labeled<Labeled<Labeled<Labeled<Counter>>>> newCounter(MetricDescriptor descriptor,
                                                         String firstLabelName, String secondLabelName,
                                                         String thirdLabelName, String fourthLabelName) {
        return firstLabelValue -> secondLabelValue -> thirdLabelValue -> fourthLabelValue ->
                newOrExistingCounter(descriptor.anchor(), makeCodahaleID(descriptor) + "{"
                        + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue  + ","
                        + thirdLabelName + "=" + thirdLabelValue + ","
                        + fourthLabelName + "=" + fourthLabelValue + "}");
    }

    @Override
    public Labeled<Labeled<Labeled<Labeled<Labeled<Counter>>>>> newCounter(MetricDescriptor descriptor,
                                                                  String firstLabelName, String secondLabelName,
                                                                  String thirdLabelName, String fourthLabelName,
                                                                  String fifthLabelName) {
        return firstLabelValue -> secondLabelValue -> thirdLabelValue -> fourthLabelValue -> fifthLabelValue ->
                newOrExistingCounter(descriptor.anchor(), makeCodahaleID(descriptor) + "{"
                        + firstLabelName + "=" + firstLabelValue + "," + secondLabelName + "=" + secondLabelValue  + ","
                        + thirdLabelName + "=" + thirdLabelValue + "," + fourthLabelName + "=" + fourthLabelValue + ","
                        + fifthLabelName + "=" + fifthLabelValue + "}");
    }

    private org.opendaylight.infrautils.metrics.Timer newOrExistingTimer(Object anchor, String id) {
        return timers.computeIfAbsent(id, newId -> {
            LOG.debug("New Timer metric: {}", id);
            return new TimerImpl(newId);
        });
    }

    @Override
    public org.opendaylight.infrautils.metrics.Timer newTimer(Object anchor, String id) {
        requireNonNull(anchor, "anchor == null");
        checkForExistingID(id);
        return newOrExistingTimer(anchor, id);
    }

    @Override
    public Timer newTimer(MetricDescriptor descriptor) {
        return newOrExistingTimer(descriptor.anchor(), makeCodahaleID(descriptor));
    }

    @Override
    public Labeled<Timer> newTimer(MetricDescriptor descriptor, String labelName) {
        return labelValue -> newOrExistingTimer(descriptor.anchor(),
                makeCodahaleID(descriptor) + "{" + labelName + "=" + labelValue + "}");
    }

    @Override
    public Labeled<Labeled<Timer>> newTimer(MetricDescriptor descriptor,
                                                String firstLabelName, String secondLabelName) {
        return firstLabelValue -> secondLabelValue -> newOrExistingTimer(descriptor.anchor(),
                makeCodahaleID(descriptor) + "{" + firstLabelName + "=" + firstLabelValue + ","
                        + secondLabelName + "=" + secondLabelValue + "}");
    }

    private static String makeCodahaleID(MetricDescriptor descriptor) {
        // We're ignoring descriptor.description() because Codahale Dropwizard Metrics
        // doesn't have it but other future metrics API implementations (e.g. Prometheus.io), or a
        // possible future metrics:list kind of CLI here, will use it.
        return MetricRegistry.name(descriptor.project(), descriptor.module(), descriptor.id());
    }

    private void checkForExistingID(String id) {
        requireNonNull(id, "id == null");
        if (registry.getNames().contains(id)) {
            throw new IllegalArgumentException("Metric ID already used: " + id);
        }
    }

    private abstract class CloseableMetricImpl implements UncheckedCloseable {
        private volatile boolean isClosed = false;
        protected final String id;

        CloseableMetricImpl(String id) {
            this.id = id;
        }

        protected void checkIfClosed() {
            if (isClosed) {
                throw new IllegalStateException("Metric closed: " + id);
            }
        }

        @Override
        public void close() {
            checkIfClosed();
            isClosed = true;
            if (!registry.remove(id)) {
                LOG.warn("Metric remove did not actualy remove: {}", id);
            }
        }
    }

    private final class MeterImpl extends CloseableMetricImpl implements org.opendaylight.infrautils.metrics.Meter {

        private final com.codahale.metrics.Meter meter;

        MeterImpl(String id) {
            super(id);
            this.meter = registry.meter(id);
        }

        @Override
        public void mark() {
            checkIfClosed();
            meter.mark();
        }

        @Override
        public void mark(long howMany) {
            checkIfClosed();
            meter.mark(howMany);
        }

        @Override
        public long get() {
            return meter.getCount();
        }

        @Override
        public void close() {
            super.close();
            meters.remove(id);
        }
    }

    private final class CounterImpl extends CloseableMetricImpl
            implements org.opendaylight.infrautils.metrics.Counter {

        private final com.codahale.metrics.Counter counter;

        CounterImpl(String id) {
            super(id);
            this.counter = registry.counter(id);
        }

        @Override
        public void increment() {
            checkIfClosed();
            counter.inc();
        }

        @Override
        public void increment(long howMany) {
            checkIfClosed();
            counter.inc(howMany);
        }

        @Override
        public void decrement() {
            checkIfClosed();
            counter.dec();
        }

        @Override
        public void decrement(long howMany) {
            checkIfClosed();
            counter.dec(howMany);
        }

        @Override
        public long get() {
            return counter.getCount();
        }

        @Override
        public void close() {
            super.close();
            counters.remove(id);
        }
    }

    private class TimerImpl extends CloseableMetricImpl implements org.opendaylight.infrautils.metrics.Timer {

        private final com.codahale.metrics.Timer timer;

        TimerImpl(String id) {
            super(id);
            this.timer = registry.timer(id);
        }

        @Override
        @SuppressWarnings({ "checkstyle:IllegalCatch", "unchecked" })
        public <T, E extends Exception> T time(CheckedCallable<T, E> event) throws E {
            checkIfClosed();
            try {
                return timer.time(event::call);
            } catch (Exception e) {
                throw (E) e;
            }
        }

        @Override
        @SuppressWarnings({ "checkstyle:IllegalCatch", "checkstyle:AvoidHidingCauseException", "unchecked" })
        @SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE") // getCause() will be Exception not Throwable
        public <E extends Exception> void time(CheckedRunnable<E> event) throws E {
            checkIfClosed();
            try {
                timer.time(() -> {
                    try {
                        event.run();
                    } catch (Exception exception) {
                        throw new InternalRuntimeException(exception);
                    }
                });
            } catch (InternalRuntimeException e) {
                throw (E) e.getCause();
            }
        }
    }

    private static class InternalRuntimeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InternalRuntimeException(Exception exception) {
            super(exception);
        }
    }

}
