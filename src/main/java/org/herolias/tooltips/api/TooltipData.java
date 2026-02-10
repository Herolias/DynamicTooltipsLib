package org.herolias.tooltips.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Carries one provider's contribution to an item's tooltip.
 * <p>
 * Use the {@link Builder} for ergonomic construction:
 * <pre>{@code
 * TooltipData data = TooltipData.builder()
 *     .addLine("<color is=\"#C8A2FF\">Enchantments:</color>")
 *     .addLine("<color is=\"#AA55FF\">• Sharpness III</color>")
 *     .hashInput("sharpness:3")
 *     .build();
 * }</pre>
 *
 * <h2>Three modes</h2>
 * <ol>
 *   <li><b>Additive lines</b> — appended after the original description. Multiple
 *       providers can add lines; they are composed in priority order.</li>
 *   <li><b>Name override</b> — replaces the item's display name.
 *       <em>Destructive:</em> highest-priority provider wins.</li>
 *   <li><b>Description override</b> — replaces the <em>entire</em> description
 *       (original + all additive lines from other providers are discarded).
 *       <em>Destructive:</em> highest-priority provider wins.</li>
 * </ol>
 */
public final class TooltipData {

    private final List<String> lines;
    private final String nameOverride;
    private final String descriptionOverride;
    private final String stableHashInput;

    private TooltipData(Builder builder) {
        this.lines = Collections.unmodifiableList(new ArrayList<>(builder.lines));
        this.nameOverride = builder.nameOverride;
        this.descriptionOverride = builder.descriptionOverride;
        this.stableHashInput = builder.stableHashInput;
    }

    /**
     * Additive lines to append after the original description.
     * Each string may contain Hytale markup (e.g. {@code <color is="#AA55FF">text</color>}).
     * Lines are joined with {@code \n} when rendered.
     */
    @Nonnull
    public List<String> getLines() {
        return lines;
    }

    /**
     * If non-null, replaces the item's display name.
     * <b>Destructive</b> — highest-priority provider wins.
     */
    @Nullable
    public String getNameOverride() {
        return nameOverride;
    }

    /**
     * If non-null, replaces the <em>entire</em> tooltip description.
     * All additive lines from all providers are discarded.
     * <b>Destructive</b> — highest-priority provider wins.
     */
    @Nullable
    public String getDescriptionOverride() {
        return descriptionOverride;
    }

    /**
     * A deterministic string used for virtual-ID hashing.
     * <p>
     * The library combines the hash inputs from all contributing providers
     * to generate a unique virtual item ID. Two items with the same base
     * type and the same combined hash inputs will share a virtual ID
     * (and hence a cached tooltip).
     * <p>
     * Must be stable: the same item state must always produce the same string.
     * Example: {@code "sharpness:3,durability:2"}.
     */
    @Nonnull
    public String getStableHashInput() {
        return stableHashInput;
    }

    /** Whether this data only has additive lines (no destructive overrides). */
    public boolean isAdditive() {
        return nameOverride == null && descriptionOverride == null;
    }

    /** Whether this data has any content at all. */
    public boolean isEmpty() {
        return lines.isEmpty() && nameOverride == null && descriptionOverride == null;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link TooltipData}.
     */
    public static final class Builder {
        private final List<String> lines = new ArrayList<>();
        private String nameOverride;
        private String descriptionOverride;
        private String stableHashInput = "";

        private Builder() {}

        /**
         * Adds an additive line to the tooltip.
         * May contain Hytale markup like {@code <color is="#AA55FF">text</color>}.
         */
        @Nonnull
        public Builder addLine(@Nonnull String line) {
            this.lines.add(line);
            return this;
        }

        /**
         * Adds multiple additive lines.
         */
        @Nonnull
        public Builder addLines(@Nonnull List<String> lines) {
            this.lines.addAll(lines);
            return this;
        }

        /**
         * Sets a name override. <b>Destructive</b> — highest-priority provider wins.
         */
        @Nonnull
        public Builder nameOverride(@Nonnull String name) {
            this.nameOverride = name;
            return this;
        }

        /**
         * Sets a full description override. <b>Destructive</b> — replaces the
         * entire tooltip including all additive lines from other providers.
         */
        @Nonnull
        public Builder descriptionOverride(@Nonnull String description) {
            this.descriptionOverride = description;
            return this;
        }

        /**
         * Sets the stable hash input for virtual-ID generation.
         * <p>
         * <b>Required.</b> Must be deterministic for the same item state.
         */
        @Nonnull
        public Builder hashInput(@Nonnull String hashInput) {
            this.stableHashInput = hashInput;
            return this;
        }

        @Nonnull
        public TooltipData build() {
            return new TooltipData(this);
        }
    }
}
