package org.herolias.tooltips.api;

import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.ModelParticle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Defines a set of visual overrides to apply to a virtual item's {@code ItemBase}.
 * <p>
 * Use the {@link Builder} to construct an instance. All fields are optional;
 * only non-null values will override the original item's properties.
 */
public final class ItemVisualOverrides {

    @Nullable private final String model;
    @Nullable private final String texture;
    @Nullable private final String icon;
    @Nullable private final String animation;
    @Nullable private final Integer soundEventIndex;
    @Nullable private final Float scale;
    @Nullable private final Integer qualityIndex;
    @Nullable private final ColorLight light;
    @Nullable private final ModelParticle[] particles;

    private ItemVisualOverrides(Builder builder) {
        this.model = builder.model;
        this.texture = builder.texture;
        this.icon = builder.icon;
        this.animation = builder.animation;
        this.soundEventIndex = builder.soundEventIndex;
        this.scale = builder.scale;
        this.qualityIndex = builder.qualityIndex;
        this.light = builder.light;
        this.particles = builder.particles;
    }

    // Getters

    @Nullable public String getModel() { return model; }
    @Nullable public String getTexture() { return texture; }
    @Nullable public String getIcon() { return icon; }
    @Nullable public String getAnimation() { return animation; }
    @Nullable public Integer getSoundEventIndex() { return soundEventIndex; }
    @Nullable public Float getScale() { return scale; }
    @Nullable public Integer getQualityIndex() { return qualityIndex; }
    @Nullable public ColorLight getLight() { return light; }
    @Nullable public ModelParticle[] getParticles() { return particles; }

    /**
     * Returns true if this instance has no overrides set.
     */
    public boolean isEmpty() {
        return model == null && texture == null && icon == null && animation == null
                && soundEventIndex == null && scale == null && qualityIndex == null
                && light == null && particles == null;
    }

    /**
     * Generates a stable hash string representing these overrides.
     * This is crucial for virtual ID generation.
     */
    public void appendHashInput(@Nonnull StringBuilder sb) {
        if (model != null) sb.append("|md:").append(model);
        if (texture != null) sb.append("|tx:").append(texture);
        if (icon != null) sb.append("|ic:").append(icon);
        if (animation != null) sb.append("|an:").append(animation);
        if (soundEventIndex != null) sb.append("|snd:").append(soundEventIndex);
        if (scale != null) sb.append("|sc:").append(scale);
        if (qualityIndex != null) sb.append("|q:").append(qualityIndex);
        if (light != null) sb.append("|lt:").append(light.hashCode()); // Rough hash for complex obj
        if (particles != null) sb.append("|pt:").append(particles.length); // Rough hash
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private String texture;
        private String icon;
        private String animation;
        private Integer soundEventIndex;
        private Float scale;
        private Integer qualityIndex;
        private ColorLight light;
        private ModelParticle[] particles;

        private Builder() {}

        @Nonnull public Builder model(@Nullable String model) { this.model = model; return this; }
        @Nonnull public Builder texture(@Nullable String texture) { this.texture = texture; return this; }
        @Nonnull public Builder icon(@Nullable String icon) { this.icon = icon; return this; }
        @Nonnull public Builder animation(@Nullable String animation) { this.animation = animation; return this; }
        @Nonnull public Builder soundEventIndex(@Nullable Integer index) { this.soundEventIndex = index; return this; }
        @Nonnull public Builder scale(@Nullable Float scale) { this.scale = scale; return this; }
        @Nonnull public Builder qualityIndex(@Nullable Integer index) { this.qualityIndex = index; return this; }
        @Nonnull public Builder light(@Nullable ColorLight light) { this.light = light; return this; }
        @Nonnull public Builder particles(@Nullable ModelParticle[] particles) { this.particles = particles; return this; }

        @Nonnull
        public ItemVisualOverrides build() {
            return new ItemVisualOverrides(this);
        }
    }
}
