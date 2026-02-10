package org.herolias.tooltips.api;

import javax.annotation.Nullable;

/**
 * Static accessor for the {@link DynamicTooltipsApi}.
 * <p>
 * The library registers the API instance during its {@code setup()} phase.
 * Mods should call {@link #get()} to obtain the API — it returns {@code null}
 * if the library is not installed or not yet initialized.
 *
 * <pre>{@code
 * DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
 * if (api != null) {
 *     api.registerProvider(new MyTooltipProvider());
 * }
 * }</pre>
 */
public final class DynamicTooltipsApiProvider {

    private static volatile DynamicTooltipsApi instance;

    private DynamicTooltipsApiProvider() {}

    /**
     * Gets the current API instance, or {@code null} if the library
     * is not loaded.
     */
    @Nullable
    public static DynamicTooltipsApi get() {
        return instance;
    }

    /**
     * Registers the API instance. Called internally by the library during setup.
     * <b>Not intended for external use.</b>
     */
    public static void register(DynamicTooltipsApi api) {
        instance = api;
    }
}
