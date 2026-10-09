package com.campustrade.observability;

import com.campustrade.order.timeout.TimeoutQueueSnapshot;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Sampled gauges keep Prometheus scrapes independent of Redis availability. */
@Component
public class OrderTimeoutMetrics implements MeterBinder {

    private final LongAdder claimed = new LongAdder();
    private final LongAdder recovered = new LongAdder();
    private final LongAdder compensated = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder backendFailures = new LongAdder();
    private final LongAdder fallbackProcessed = new LongAdder();
    private final AtomicReference<TimeoutQueueSnapshot> snapshot = new AtomicReference<>();
    private volatile double lastScanSuccessEpochSeconds;
    private volatile double queueAvailable;
    private volatile Timer closeDelay;

    @Override
    public void bindTo(MeterRegistry registry) {
        counter(registry, "claimed", claimed);
        counter(registry, "recovered", recovered);
        counter(registry, "compensated", compensated);
        counter(registry, "retried", retried);
        counter(registry, "expired", expired);
        counter(registry, "backend.failures", backendFailures);
        counter(registry, "fallback.processed", fallbackProcessed);
        Gauge.builder("campustrade.order.timeout.pending", this,
                metrics -> metrics.snapshot.get() == null ? Double.NaN : metrics.snapshot.get().pending()).register(registry);
        Gauge.builder("campustrade.order.timeout.processing", this,
                metrics -> metrics.snapshot.get() == null ? Double.NaN : metrics.snapshot.get().processing()).register(registry);
        Gauge.builder("campustrade.order.timeout.expired.leases", this,
                metrics -> metrics.snapshot.get() == null ? Double.NaN : metrics.snapshot.get().expiredLeases()).register(registry);
        Gauge.builder("campustrade.order.timeout.oldest.due.age", this,
                metrics -> metrics.snapshot.get() == null ? Double.NaN : metrics.snapshot.get().oldestDueAgeSeconds())
                .baseUnit("seconds").register(registry);
        Gauge.builder("campustrade.order.timeout.scan.last.success", this,
                metrics -> metrics.lastScanSuccessEpochSeconds).baseUnit("seconds").register(registry);
        Gauge.builder("campustrade.order.timeout.queue.available", this,
                metrics -> metrics.queueAvailable).register(registry);
        closeDelay = Timer.builder("campustrade.order.timeout.close.delay")
                .description("Delay between database order deadline and successful expiration")
                .publishPercentileHistogram().register(registry);
    }

    private void counter(MeterRegistry registry, String suffix, LongAdder value) {
        FunctionCounter.builder("campustrade.order.timeout." + suffix, value, LongAdder::doubleValue).register(registry);
    }

    public void claimed(int count) { claimed.add(count); }
    public void recovered(int count) { recovered.add(count); }
    public void compensated(int count) { compensated.add(count); }
    public void retried() { retried.increment(); }
    public void expired() { expired.increment(); }
    public void fallbackProcessed() { fallbackProcessed.increment(); }
    public void backendFailure() {
        backendFailures.increment();
        queueAvailable = 0;
        snapshot.set(null);
    }
    public void scanSucceeded(TimeoutQueueSnapshot current, double epochSeconds) {
        snapshot.set(current);
        queueAvailable = 1;
        lastScanSuccessEpochSeconds = epochSeconds;
    }
    public void closeDelay(Duration delay) {
        Timer timer = closeDelay;
        if (timer != null && !delay.isNegative()) {
            timer.record(delay);
        }
    }
}
