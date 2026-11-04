package com.github.beng420.kung.feature;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.runtime.AppServices;
import java.util.function.Function;

public abstract class ConfigurableFeature<C> implements Feature {
    private final Function<KungConfig, C> configSelector;
    private AppServices services;
    private boolean initialized;

    protected ConfigurableFeature(Function<KungConfig, C> configSelector) {
        this.configSelector = java.util.Objects.requireNonNull(configSelector);
    }

    @Override
    public final void initialize(AppServices services) {
        if (initialized) return;
        this.services = java.util.Objects.requireNonNull(services);
        onInitialize();
        initialized = true;
    }

    protected abstract void onInitialize();

    protected void onReset() {
    }

    /** Runs once when the client stops; by default the same as {@link #onReset}. */
    protected void onShutdown() {
        onReset();
    }

    @Override
    public final void reset() {
        if (initialized) {
            onReset();
        }
    }

    @Override
    public final void shutdown() {
        if (!initialized) {
            return;
        }
        onShutdown();
        initialized = false;
        services = null;
    }

    public final boolean initialized() {
        return initialized;
    }

    /**
     * Always returns the active feature-specific config section.
     */
    protected C config() {
        return configSelector.apply(requireServices().config());
    }

    protected final AppServices services() {
        return requireServices();
    }

    private AppServices requireServices() {
        if (services == null) {
            throw new IllegalStateException("Feature has not been initialized.");
        }
        return services;
    }
}
