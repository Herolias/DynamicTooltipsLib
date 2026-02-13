package org.herolias.tooltips.api;

import com.hypixel.hytale.protocol.AssetIconProperties;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.ItemAppearanceCondition;
import com.hypixel.hytale.protocol.ItemPullbackConfiguration;
import com.hypixel.hytale.protocol.ModelParticle;
import com.hypixel.hytale.protocol.ModelTrail;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Defines a set of visual overrides to apply to a virtual item's {@code ItemBase}.
 * <p>
 * Use the {@link Builder} to construct an instance. All fields are optional;
 * only non-null values will override the original item's properties.
 */
public final class ItemVisualOverrides {

    // ── Existing visual overrides ──
    @Nullable private final String model;
    @Nullable private final String texture;
    @Nullable private final String icon;
    @Nullable private final String animation;
    @Nullable private final Integer soundEventIndex;
    @Nullable private final Float scale;
    @Nullable private final Integer qualityIndex;
    @Nullable private final ColorLight light;
    @Nullable private final ModelParticle[] particles;
    @Nullable private final String playerAnimationsId;
    @Nullable private final Boolean usePlayerAnimations;

    // ── New visual overrides ──
    @Nullable private final Integer reticleIndex;
    @Nullable private final AssetIconProperties iconProperties;
    @Nullable private final ModelParticle[] firstPersonParticles;
    @Nullable private final ModelTrail[] trails;
    @Nullable private final String droppedItemAnimation;
    @Nullable private final Integer itemSoundSetIndex;
    @Nullable private final Map<Integer, ItemAppearanceCondition[]> itemAppearanceConditions;
    @Nullable private final ItemPullbackConfiguration pullbackConfig;
    @Nullable private final Boolean clipsGeometry;
    @Nullable private final Boolean renderDeployablePreview;
    @Nullable private final String set;
    @Nullable private final String[] categories;
    @Nullable private final int[] displayEntityStatsHUD;

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
        this.playerAnimationsId = builder.playerAnimationsId;
        this.usePlayerAnimations = builder.usePlayerAnimations;
        this.reticleIndex = builder.reticleIndex;
        this.iconProperties = builder.iconProperties;
        this.firstPersonParticles = builder.firstPersonParticles;
        this.trails = builder.trails;
        this.droppedItemAnimation = builder.droppedItemAnimation;
        this.itemSoundSetIndex = builder.itemSoundSetIndex;
        this.itemAppearanceConditions = builder.itemAppearanceConditions;
        this.pullbackConfig = builder.pullbackConfig;
        this.clipsGeometry = builder.clipsGeometry;
        this.renderDeployablePreview = builder.renderDeployablePreview;
        this.set = builder.set;
        this.categories = builder.categories;
        this.displayEntityStatsHUD = builder.displayEntityStatsHUD;
    }

    // ── Getters (existing) ──

    @Nullable public String getModel() { return model; }
    @Nullable public String getTexture() { return texture; }
    @Nullable public String getIcon() { return icon; }
    @Nullable public String getAnimation() { return animation; }
    @Nullable public Integer getSoundEventIndex() { return soundEventIndex; }
    @Nullable public Float getScale() { return scale; }
    @Nullable public Integer getQualityIndex() { return qualityIndex; }
    @Nullable public ColorLight getLight() { return light; }
    @Nullable public ModelParticle[] getParticles() { return particles; }
    @Nullable public String getPlayerAnimationsId() { return playerAnimationsId; }
    @Nullable public Boolean getUsePlayerAnimations() { return usePlayerAnimations; }

    // ── Getters (new) ──

    @Nullable public Integer getReticleIndex() { return reticleIndex; }
    @Nullable public AssetIconProperties getIconProperties() { return iconProperties; }
    @Nullable public ModelParticle[] getFirstPersonParticles() { return firstPersonParticles; }
    @Nullable public ModelTrail[] getTrails() { return trails; }
    @Nullable public String getDroppedItemAnimation() { return droppedItemAnimation; }
    @Nullable public Integer getItemSoundSetIndex() { return itemSoundSetIndex; }
    @Nullable public Map<Integer, ItemAppearanceCondition[]> getItemAppearanceConditions() { return itemAppearanceConditions; }
    @Nullable public ItemPullbackConfiguration getPullbackConfig() { return pullbackConfig; }
    @Nullable public Boolean getClipsGeometry() { return clipsGeometry; }
    @Nullable public Boolean getRenderDeployablePreview() { return renderDeployablePreview; }
    @Nullable public String getSet() { return set; }
    @Nullable public String[] getCategories() { return categories; }
    @Nullable public int[] getDisplayEntityStatsHUD() { return displayEntityStatsHUD; }

    /**
     * Returns true if this instance has no overrides set.
     */
    public boolean isEmpty() {
        return model == null && texture == null && icon == null && animation == null
                && soundEventIndex == null && scale == null && qualityIndex == null
                && light == null && particles == null && playerAnimationsId == null
                && usePlayerAnimations == null
                && reticleIndex == null && iconProperties == null
                && firstPersonParticles == null && trails == null
                && droppedItemAnimation == null && itemSoundSetIndex == null
                && itemAppearanceConditions == null && pullbackConfig == null
                && clipsGeometry == null && renderDeployablePreview == null
                && set == null && categories == null && displayEntityStatsHUD == null;
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
        if (light != null) sb.append("|lt:").append(light.hashCode());
        if (particles != null) sb.append("|pt:").append(particles.length);
        if (playerAnimationsId != null) sb.append("|pa:").append(playerAnimationsId);
        if (usePlayerAnimations != null) sb.append("|upa:").append(usePlayerAnimations);
        if (reticleIndex != null) sb.append("|ret:").append(reticleIndex);
        if (iconProperties != null) sb.append("|ip:").append(iconProperties.hashCode());
        if (firstPersonParticles != null) sb.append("|fpp:").append(firstPersonParticles.length);
        if (trails != null) sb.append("|tr:").append(trails.length);
        if (droppedItemAnimation != null) sb.append("|dia:").append(droppedItemAnimation);
        if (itemSoundSetIndex != null) sb.append("|issi:").append(itemSoundSetIndex);
        if (itemAppearanceConditions != null) sb.append("|iac:").append(itemAppearanceConditions.size());
        if (pullbackConfig != null) sb.append("|pb:").append(pullbackConfig.hashCode());
        if (clipsGeometry != null) sb.append("|cg:").append(clipsGeometry);
        if (renderDeployablePreview != null) sb.append("|rdp:").append(renderDeployablePreview);
        if (set != null) sb.append("|set:").append(set);
        if (categories != null) sb.append("|cat:").append(Arrays.hashCode(categories));
        if (displayEntityStatsHUD != null) sb.append("|desh:").append(Arrays.hashCode(displayEntityStatsHUD));
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        // Existing
        private String model;
        private String texture;
        private String icon;
        private String animation;
        private Integer soundEventIndex;
        private Float scale;
        private Integer qualityIndex;
        private ColorLight light;
        private ModelParticle[] particles;
        private String playerAnimationsId;
        private Boolean usePlayerAnimations;
        // New
        private Integer reticleIndex;
        private AssetIconProperties iconProperties;
        private ModelParticle[] firstPersonParticles;
        private ModelTrail[] trails;
        private String droppedItemAnimation;
        private Integer itemSoundSetIndex;
        private Map<Integer, ItemAppearanceCondition[]> itemAppearanceConditions;
        private ItemPullbackConfiguration pullbackConfig;
        private Boolean clipsGeometry;
        private Boolean renderDeployablePreview;
        private String set;
        private String[] categories;
        private int[] displayEntityStatsHUD;

        private Builder() {}

        // ── Existing builder methods ──
        @Nonnull public Builder model(@Nullable String model) { this.model = model; return this; }
        @Nonnull public Builder texture(@Nullable String texture) { this.texture = texture; return this; }
        @Nonnull public Builder icon(@Nullable String icon) { this.icon = icon; return this; }
        @Nonnull public Builder animation(@Nullable String animation) { this.animation = animation; return this; }
        @Nonnull public Builder soundEventIndex(@Nullable Integer index) { this.soundEventIndex = index; return this; }
        @Nonnull public Builder scale(@Nullable Float scale) { this.scale = scale; return this; }
        @Nonnull public Builder qualityIndex(@Nullable Integer index) { this.qualityIndex = index; return this; }
        @Nonnull public Builder light(@Nullable ColorLight light) { this.light = light; return this; }
        @Nonnull public Builder particles(@Nullable ModelParticle[] particles) { this.particles = particles; return this; }
        @Nonnull public Builder playerAnimationsId(@Nullable String playerAnimationsId) { this.playerAnimationsId = playerAnimationsId; return this; }
        @Nonnull public Builder usePlayerAnimations(@Nullable Boolean usePlayerAnimations) { this.usePlayerAnimations = usePlayerAnimations; return this; }

        // ── New builder methods ──
        /** Override the crosshair/reticle graphic index. */
        @Nonnull public Builder reticleIndex(@Nullable Integer index) { this.reticleIndex = index; return this; }
        /** Override icon display properties (scale, translation, rotation in UI). */
        @Nonnull public Builder iconProperties(@Nullable AssetIconProperties iconProperties) { this.iconProperties = iconProperties; return this; }
        /** Override first-person-only particle effects. */
        @Nonnull public Builder firstPersonParticles(@Nullable ModelParticle[] particles) { this.firstPersonParticles = particles; return this; }
        /** Override visual trail effects on the item model. */
        @Nonnull public Builder trails(@Nullable ModelTrail[] trails) { this.trails = trails; return this; }
        /** Override the animation played when this item is dropped on the ground. */
        @Nonnull public Builder droppedItemAnimation(@Nullable String animation) { this.droppedItemAnimation = animation; return this; }
        /** Override the sound set index for item interaction sounds. */
        @Nonnull public Builder itemSoundSetIndex(@Nullable Integer index) { this.itemSoundSetIndex = index; return this; }
        /** Override conditional appearance states (e.g. charge-level visuals). */
        @Nonnull public Builder itemAppearanceConditions(@Nullable Map<Integer, ItemAppearanceCondition[]> conditions) { this.itemAppearanceConditions = conditions; return this; }
        /** Override pullback position config for bows/crossbows. */
        @Nonnull public Builder pullbackConfig(@Nullable ItemPullbackConfiguration config) { this.pullbackConfig = config; return this; }
        /** Override whether the item clips through world geometry. */
        @Nonnull public Builder clipsGeometry(@Nullable Boolean clipsGeometry) { this.clipsGeometry = clipsGeometry; return this; }
        /** Override whether a deployable placement preview is rendered. */
        @Nonnull public Builder renderDeployablePreview(@Nullable Boolean renderDeployablePreview) { this.renderDeployablePreview = renderDeployablePreview; return this; }
        /** Override the item set membership (visual grouping). */
        @Nonnull public Builder set(@Nullable String set) { this.set = set; return this; }
        /** Override the creative library category tabs this item appears in. */
        @Nonnull public Builder categories(@Nullable String[] categories) { this.categories = categories; return this; }
        /** Override which entity stats are displayed on the HUD for this item. */
        @Nonnull public Builder displayEntityStatsHUD(@Nullable int[] statsHUD) { this.displayEntityStatsHUD = statsHUD; return this; }

        @Nonnull
        public ItemVisualOverrides build() {
            return new ItemVisualOverrides(this);
        }
    }
}
