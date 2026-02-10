package org.herolias.tooltips.api;

/**
 * Standard priority constants for {@link TooltipProvider#getPriority()}.
 * <p>
 * Providers are rendered in ascending priority order: lower values appear
 * closer to the original item description, higher values appear further
 * below. For destructive overrides (name/description), the
 * <b>highest-priority</b> provider wins.
 *
 * <pre>
 *   ┌─────────────────────────────────────┐
 *   │  Original item description          │
 *   │  ─── FIRST (0) providers ───        │
 *   │  ─── EARLY (50) providers ───       │
 *   │  ─── DEFAULT (100) providers ───    │
 *   │  ─── LATE (150) providers ───       │
 *   │  ─── LAST (200) providers ───       │
 *   └─────────────────────────────────────┘
 * </pre>
 */
public final class TooltipPriority {

    private TooltipPriority() {}

    /** Rendered closest to the original description. */
    public static final int FIRST = 0;

    /** Rendered before default providers. */
    public static final int EARLY = 50;

    /** Standard priority — use this unless you have a reason to go earlier/later. */
    public static final int DEFAULT = 100;

    /** Rendered after default providers. */
    public static final int LATE = 150;

    /** Rendered furthest from the original description. */
    public static final int LAST = 200;

    /**
     * Reserved for providers whose sole purpose is a destructive
     * name or description override. Guarantees highest priority for
     * the override resolution.
     */
    public static final int OVERRIDE = 999;
}
