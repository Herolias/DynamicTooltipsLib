package org.herolias.tooltips.api;

/**
 * Standard BSON metadata keys that the built-in tooltip provider reads.
 * <p>
 * Any mod can write these keys into an item's metadata (via Hytale's
 * {@code ItemStack} API) and the library will automatically display them
 * without the mod having to register its own {@link TooltipProvider}.
 *
 * <h2>Example metadata (BSON/JSON)</h2>
 * <pre>{@code
 * {
 *   "dtt_name": "Flame Sword",
 *   "dtt_lines": [
 *     "<color is=\"#FF5555\">Burns enemies on hit</color>",
 *     "<color is=\"#AAAAAA\">Fire Damage +5</color>"
 *   ]
 * }
 * }</pre>
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>{@link #CUSTOM_NAME} — sets a <b>name override</b> at priority
 *       {@link TooltipPriority#LAST}. This is destructive: the highest-priority
 *       name override wins.</li>
 *   <li>{@link #CUSTOM_LINES} — adds <b>additive lines</b> at priority
 *       {@link TooltipPriority#LAST}. These appear after all other providers'
 *       lines, and coexist with them.</li>
 * </ul>
 */
public final class CustomTooltipKeys {

    private CustomTooltipKeys() {}

    /**
     * Metadata key for a custom display name.
     * <p>
     * Value: a BSON string. May contain Hytale markup
     * (e.g. {@code <color is="#FF5555">Flame Sword</color>}).
     */
    public static final String CUSTOM_NAME = "dtt_name";

    /**
     * Metadata key for custom additive tooltip lines.
     * <p>
     * Value: a BSON array of strings. Each string is one line that will
     * be appended after the original description and after other providers'
     * additive lines (since the built-in provider uses
     * {@link TooltipPriority#LAST}).
     */
    public static final String CUSTOM_LINES = "dtt_lines";
}
