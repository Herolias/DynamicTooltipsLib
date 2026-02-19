package org.herolias.tooltips.api;

import com.hypixel.hytale.protocol.AssetIconProperties;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.ItemAppearanceCondition;
import com.hypixel.hytale.protocol.ItemArmor;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.ItemEntityConfig;
import com.hypixel.hytale.protocol.ItemPullbackConfiguration;
import com.hypixel.hytale.protocol.ItemTool;
import com.hypixel.hytale.protocol.ItemWeapon;
import com.hypixel.hytale.protocol.ModelParticle;
import com.hypixel.hytale.protocol.ModelTrail;
import com.hypixel.hytale.protocol.Modifier;

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
    @Nullable private final ItemEntityConfig itemEntity;
    @Nullable private final Double durability;

    // ── Stat / tooltip structure overrides ──
    @Nullable private final ItemArmor armor;
    @Nullable private final ItemWeapon weapon;
    @Nullable private final ItemTool tool;

    // ── Additive stat modifier maps (merged with original) ──
    @Nullable private final Map<Integer, Modifier[]> additionalArmorStatModifiers;
    @Nullable private final Map<Integer, Modifier[]> additionalWeaponStatModifiers;

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
        this.itemEntity = builder.itemEntity;
        this.durability = builder.durability;
        this.armor = builder.armor;
        this.weapon = builder.weapon;
        this.tool = builder.tool;
        this.additionalArmorStatModifiers = builder.additionalArmorStatModifiers;
        this.additionalWeaponStatModifiers = builder.additionalWeaponStatModifiers;
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
    @Nullable public ItemEntityConfig getItemEntity() { return itemEntity; }
    @Nullable public Double getDurability() { return durability; }

    // ── Getters (stat/tooltip structure overrides) ──

    /** Returns the armor override, or {@code null} if not set. */
    @Nullable public ItemArmor getArmor() { return armor; }
    /** Returns the weapon override, or {@code null} if not set. */
    @Nullable public ItemWeapon getWeapon() { return weapon; }
    /** Returns the tool override, or {@code null} if not set. */
    @Nullable public ItemTool getTool() { return tool; }

    // ── Getters (additive stat modifiers) ──

    /** Returns additive armor stat modifiers to merge with the original, or {@code null}. */
    @Nullable public Map<Integer, Modifier[]> getAdditionalArmorStatModifiers() { return additionalArmorStatModifiers; }
    /** Returns additive weapon stat modifiers to merge with the original, or {@code null}. */
    @Nullable public Map<Integer, Modifier[]> getAdditionalWeaponStatModifiers() { return additionalWeaponStatModifiers; }

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
                && set == null && categories == null && displayEntityStatsHUD == null
                && itemEntity == null && durability == null
                && armor == null && weapon == null && tool == null
                && additionalArmorStatModifiers == null && additionalWeaponStatModifiers == null;
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
        if (itemEntity != null) sb.append("|ie:").append(itemEntity.hashCode());
        if (durability != null) sb.append("|dur:").append(durability);
        if (armor != null) sb.append("|arm:").append(armor.hashCode());
        if (weapon != null) sb.append("|wpn:").append(weapon.hashCode());
        if (tool != null) sb.append("|tl:").append(tool.hashCode());
        if (additionalArmorStatModifiers != null) sb.append("|aasm:").append(additionalArmorStatModifiers.hashCode());
        if (additionalWeaponStatModifiers != null) sb.append("|awsm:").append(additionalWeaponStatModifiers.hashCode());
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
        private ItemEntityConfig itemEntity;
        private Double durability;
        // Stat/tooltip structure overrides
        private ItemArmor armor;
        private ItemWeapon weapon;
        private ItemTool tool;
        // Additive stat modifiers (merged with original)
        private Map<Integer, Modifier[]> additionalArmorStatModifiers;
        private Map<Integer, Modifier[]> additionalWeaponStatModifiers;

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
        /** Override the dropped item entity config (particle system, color, showItemParticles). */
        @Nonnull public Builder itemEntity(@Nullable ItemEntityConfig config) { this.itemEntity = config; return this; }
        /** Override the max durability shown in the tooltip (purely visual). */
        @Nonnull public Builder durability(@Nullable Double durability) { this.durability = durability; return this; }

        // ── Stat / tooltip structure overrides (raw protocol objects) ──

        /**
         * Override the entire armor configuration (slot, modifiers, resistances).
         * <p>For simpler use cases, see {@link #armorSlot(ItemArmorSlot)},
         * {@link #armorBaseDamageResistance(double)}, and
         * {@link #armorStatModifiers(Map)}.</p>
         */
        @Nonnull public Builder armor(@Nullable ItemArmor armor) { this.armor = armor; return this; }

        /**
         * Override the entire weapon configuration (stat modifiers, dual wield, etc.).
         * <p>For simpler use cases, see {@link #weaponStatModifiers(Map)}.</p>
         */
        @Nonnull public Builder weapon(@Nullable ItemWeapon weapon) { this.weapon = weapon; return this; }

        /**
         * Override the entire tool configuration (specs, speed).
         * <p>For a simpler use case, see {@link #toolSpeed(float)}.</p>
         */
        @Nonnull public Builder tool(@Nullable ItemTool tool) { this.tool = tool; return this; }

        // ── Convenience methods (create protocol objects under the hood) ──

        /**
         * Convenience: override only the armor slot shown in the tooltip.
         * Creates an {@link ItemArmor} internally if not already set via {@link #armor(ItemArmor)}.
         *
         * @param slot the armor slot to display (Head, Chest, Hands, Legs)
         */
        @Nonnull
        public Builder armorSlot(@Nonnull ItemArmorSlot slot) {
            ensureArmor().armorSlot = slot;
            return this;
        }

        /**
         * Convenience: override the base damage resistance shown in the armor tooltip section.
         */
        @Nonnull
        public Builder armorBaseDamageResistance(double resistance) {
            ensureArmor().baseDamageResistance = resistance;
            return this;
        }

        /**
         * Convenience: override the stat modifiers displayed in the armor tooltip section.
         * Keys are entity stat indices; values are arrays of {@link Modifier}.
         */
        @Nonnull
        public Builder armorStatModifiers(@Nullable Map<Integer, Modifier[]> modifiers) {
            ensureArmor().statModifiers = modifiers;
            return this;
        }

        /**
         * Convenience: override the damage resistance map in the armor tooltip section.
         * Keys are damage type identifiers; values are arrays of {@link Modifier}.
         */
        @Nonnull
        public Builder armorDamageResistance(@Nullable Map<String, Modifier[]> resistance) {
            ensureArmor().damageResistance = resistance;
            return this;
        }

        /**
         * Convenience: override the stat modifiers displayed in the weapon tooltip section.
         * Keys are entity stat indices; values are arrays of {@link Modifier}.
         */
        @Nonnull
        public Builder weaponStatModifiers(@Nullable Map<Integer, Modifier[]> modifiers) {
            ensureWeapon().statModifiers = modifiers;
            return this;
        }

        /**
         * Convenience: override the tool speed shown in the tooltip.
         */
        @Nonnull
        public Builder toolSpeed(float speed) {
            ensureTool().speed = speed;
            return this;
        }

        // ── Additive convenience methods (merge with original item's modifiers) ──

        /**
         * Convenience: <b>add</b> a stat modifier to the armor tooltip section,
         * preserving the original item's existing modifiers.
         * <p>Unlike {@link #armorStatModifiers(Map)}, which replaces all modifiers,
         * this method appends to the original item's modifier list.
         *
         * @param statIndex the entity stat index to modify
         * @param modifier  the modifier to add
         */
        @Nonnull
        public Builder addArmorStatModifier(int statIndex, @Nonnull Modifier modifier) {
            if (this.additionalArmorStatModifiers == null) this.additionalArmorStatModifiers = new java.util.HashMap<>();
            this.additionalArmorStatModifiers.merge(statIndex,
                    new Modifier[]{modifier},
                    ItemVisualOverrides::concatModifiers);
            return this;
        }

        /**
         * Convenience: <b>add</b> multiple stat modifiers to the armor tooltip section,
         * preserving the original item's existing modifiers.
         *
         * @param modifiers map of stat index → modifiers to add
         */
        @Nonnull
        public Builder addArmorStatModifiers(@Nonnull Map<Integer, Modifier[]> modifiers) {
            if (this.additionalArmorStatModifiers == null) this.additionalArmorStatModifiers = new java.util.HashMap<>();
            for (Map.Entry<Integer, Modifier[]> entry : modifiers.entrySet()) {
                this.additionalArmorStatModifiers.merge(entry.getKey(),
                        entry.getValue(),
                        ItemVisualOverrides::concatModifiers);
            }
            return this;
        }

        /**
         * Convenience: <b>add</b> a stat modifier to the weapon tooltip section,
         * preserving the original item's existing modifiers.
         *
         * @param statIndex the entity stat index to modify
         * @param modifier  the modifier to add
         */
        @Nonnull
        public Builder addWeaponStatModifier(int statIndex, @Nonnull Modifier modifier) {
            if (this.additionalWeaponStatModifiers == null) this.additionalWeaponStatModifiers = new java.util.HashMap<>();
            this.additionalWeaponStatModifiers.merge(statIndex,
                    new Modifier[]{modifier},
                    ItemVisualOverrides::concatModifiers);
            return this;
        }

        /**
         * Convenience: <b>add</b> multiple stat modifiers to the weapon tooltip section,
         * preserving the original item's existing modifiers.
         *
         * @param modifiers map of stat index → modifiers to add
         */
        @Nonnull
        public Builder addWeaponStatModifiers(@Nonnull Map<Integer, Modifier[]> modifiers) {
            if (this.additionalWeaponStatModifiers == null) this.additionalWeaponStatModifiers = new java.util.HashMap<>();
            for (Map.Entry<Integer, Modifier[]> entry : modifiers.entrySet()) {
                this.additionalWeaponStatModifiers.merge(entry.getKey(),
                        entry.getValue(),
                        ItemVisualOverrides::concatModifiers);
            }
            return this;
        }

        // ── Internal helpers for convenience methods ──

        @Nonnull
        private ItemArmor ensureArmor() {
            if (this.armor == null) this.armor = new ItemArmor();
            return this.armor;
        }

        @Nonnull
        private ItemWeapon ensureWeapon() {
            if (this.weapon == null) this.weapon = new ItemWeapon();
            return this.weapon;
        }

        @Nonnull
        private ItemTool ensureTool() {
            if (this.tool == null) this.tool = new ItemTool();
            return this.tool;
        }

        @Nonnull
        public ItemVisualOverrides build() {
            return new ItemVisualOverrides(this);
        }
    }

    // ── Static helper for merging modifier arrays ──

    @Nonnull
    static Modifier[] concatModifiers(@Nonnull Modifier[] a, @Nonnull Modifier[] b) {
        Modifier[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
