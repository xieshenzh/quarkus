package io.quarkus.opentelemetry.runtime;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.quarkus.vertx.core.runtime.VertxMDC;

public final class OpenTelemetryUtil {
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String SAMPLED = "sampled";
    public static final String PARENT_ID = "parentId";

    private OpenTelemetryUtil() {
    }

    private static TextMapPropagator getPropagator(
            String name, Map<String, TextMapPropagator> spiPropagators) {
        if ("tracecontext".equals(name)) {
            return W3CTraceContextPropagator.getInstance();
        }
        if ("baggage".equals(name)) {
            return W3CBaggagePropagator.getInstance();
        }

        TextMapPropagator spiPropagator = spiPropagators.get(name);
        if (spiPropagator != null) {
            return spiPropagator;
        }
        throw new IllegalArgumentException(
                "Unrecognized value for propagator: " + name
                        + ". Make sure the artifact including the propagator is on the classpath.");
    }

    public static ContextPropagators mapPropagators(List<String> propagators) {
        Map<String, TextMapPropagator> spiPropagators = StreamSupport.stream(
                ServiceLoader.load(ConfigurablePropagatorProvider.class).spliterator(), false)
                .collect(
                        Collectors.toMap(ConfigurablePropagatorProvider::getName,
                                // Even though this param was added, propagators currently don't use any config properties
                                // when the time arrives, and they do use it, we will need to implement a Quarkus
                                // backed `ConfigProperties` class
                                o -> o.getPropagator(null)));

        Set<TextMapPropagator> selectedPropagators = propagators.stream()
                .map(propagator -> getPropagator(propagator.trim(), spiPropagators))
                .collect(Collectors.toSet());

        return ContextPropagators.create(TextMapPropagator.composite(selectedPropagators));
    }

    /**
     * Converts a list of "key=value" pairs into a map.
     * Empty entries will be removed.
     * In case of duplicate keys, the latest takes precedence.
     *
     * @param headers nullable list of "key=value" pairs
     * @return non-null map of key-value pairs
     */
    public static Map<String, String> convertKeyValueListToMap(List<String> headers) {
        if (headers == null) {
            return Collections.emptyMap();
        }

        return headers.stream()
                .filter(header -> !header.isEmpty())
                .map(keyValuePair -> keyValuePair.split("=", 2))
                .map(keyValuePair -> new AbstractMap.SimpleImmutableEntry<>(keyValuePair[0].trim(), keyValuePair[1].trim()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, next) -> next, LinkedHashMap::new));
    }

    /**
     * Sets MDC data by using the current span from the context.
     *
     * @param context opentelemetry context
     * @param vertxContext vertx context
     */
    public static void setMDCData(Context context, io.vertx.core.Context vertxContext) {
        Span span = Span.fromContextOrNull(context);
        if (span != null) {
            SpanContext spanContext = span.getSpanContext();
            VertxMDC vertxMDC = VertxMDC.INSTANCE;
            vertxMDC.put(SPAN_ID, spanContext.getSpanId(), vertxContext);
            vertxMDC.put(TRACE_ID, spanContext.getTraceId(), vertxContext);
            vertxMDC.put(SAMPLED, Boolean.toString(spanContext.isSampled()), vertxContext);
            if (span instanceof ReadableSpan) {
                SpanContext parentSpanContext = ((ReadableSpan) span).getParentSpanContext();
                if (parentSpanContext.isValid()) {
                    vertxMDC.put(PARENT_ID, parentSpanContext.getSpanId(), vertxContext);
                } else {
                    vertxMDC.remove(PARENT_ID, vertxContext);
                }
            }
        }
    }

    /**
     * Clears MDC data related to OpenTelemetry
     *
     * @param vertxContext vertx context
     */
    public static void clearMDCData(io.vertx.core.Context vertxContext) {
        VertxMDC vertxMDC = VertxMDC.INSTANCE;
        vertxMDC.remove(TRACE_ID, vertxContext);
        vertxMDC.remove(SPAN_ID, vertxContext);
        vertxMDC.remove(PARENT_ID, vertxContext);
        vertxMDC.remove(SAMPLED, vertxContext);
    }
}
