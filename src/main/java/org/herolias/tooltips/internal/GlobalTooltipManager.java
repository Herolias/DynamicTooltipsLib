package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages global tooltip properties that affect all items of a specific base type,
 * without using virtual IDs. This is useful for system-wide tooltips that everyone should see.
 */
public class GlobalTooltipManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final VirtualItemRegistry virtualItemRegistry;

    // Track additive and replace lines separately. Replace takes precedence over additive.
    private final Map<String, List<GlobalTooltipLine>> additiveLines = new ConcurrentHashMap<>();
    private final Map<String, List<GlobalTooltipLine>> replacedLines = new ConcurrentHashMap<>();

    public GlobalTooltipManager(@Nonnull VirtualItemRegistry virtualItemRegistry) {
        this.virtualItemRegistry = virtualItemRegistry;
    }

    /**
     * Appends a line to the global tooltip of an item type.
     * @param baseItemId the base item ID
     * @param line the line to add
     */
    public void addGlobalLine(@Nonnull String baseItemId, @Nonnull String line) {
        additiveLines.computeIfAbsent(baseItemId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new GlobalTooltipLine(line, false));
        broadcastUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Appends a translation key line to the global tooltip of an item type.
     * @param baseItemId the base item ID
     * @param translationKey the key to add
     */
    public void addGlobalTranslationLine(@Nonnull String baseItemId, @Nonnull String translationKey) {
        additiveLines.computeIfAbsent(baseItemId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new GlobalTooltipLine(translationKey, true));
        broadcastUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of an item type with the given lines.
     * @param baseItemId the base item ID
     * @param lines the lines to replace the description with
     */
    public void replaceGlobalTooltip(@Nonnull String baseItemId, @Nonnull String[] lines) {
        List<GlobalTooltipLine> mapped = new ArrayList<>();
        for (String line : lines) mapped.add(new GlobalTooltipLine(line, false));
        replacedLines.put(baseItemId, mapped);
        broadcastUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Replaces the global tooltip of an item type with the given translation keys.
     * @param baseItemId the base item ID
     * @param translationKeys the keys to replace the description with
     */
    public void replaceGlobalTranslationTooltip(@Nonnull String baseItemId, @Nonnull String[] translationKeys) {
        List<GlobalTooltipLine> mapped = new ArrayList<>();
        for (String key : translationKeys) mapped.add(new GlobalTooltipLine(key, true));
        replacedLines.put(baseItemId, mapped);
        broadcastUpdates(Collections.singleton(baseItemId));
    }

    /**
     * Clears all global tooltip overrides for this base item type.
     * @param baseItemId the base item ID
     */
    public void clearGlobalTooltips(@Nonnull String baseItemId) {
        boolean removedAdd = additiveLines.remove(baseItemId) != null;
        boolean removedRep = replacedLines.remove(baseItemId) != null;
        if (removedAdd || removedRep) {
            broadcastUpdates(Collections.singleton(baseItemId));
        }
    }

    /**
     * Invoked when a player joins or needs a full refresh of all global items.
     * @param playerRef the player reference
     */
    public void sendAllUpdates(@Nonnull PlayerRef playerRef) {
        Set<String> allIds = new HashSet<>();
        allIds.addAll(additiveLines.keySet());
        allIds.addAll(replacedLines.keySet());
        sendUpdates(playerRef, allIds);
    }

    /**
     * Broadcasts updates to all online players.
     */
    private void broadcastUpdates(@Nonnull Set<String> baseItemIds) {
        if (Universe.get() == null) return;
        
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) continue;
            sendUpdates(playerRef, baseItemIds);
        }
    }

    /**
     * Sends the specific set of base item global updates to a given player.
     */
    private void sendUpdates(@Nonnull PlayerRef playerRef, @Nonnull Set<String> baseItemIds) {
        if (baseItemIds.isEmpty()) return;
        
        String locale = playerRef.getLanguage();
        if (locale == null || locale.isEmpty()) locale = "en-US";

        Map<String, String> translations = new HashMap<>();

        for (String baseItemId : baseItemIds) {
            String translationKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            if (translationKey == null || translationKey.trim().isEmpty()) continue;
            
            String computed = getGlobalDescription(baseItemId, locale);
            if (computed != null) {
                translations.put(translationKey, computed);
            }
        }

        if (!translations.isEmpty()) {
            try {
                UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
                if (playerRef.getPacketHandler() != null) {
                    playerRef.getPacketHandler().writeNoCache(packet);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to send global tooltip updates: " + e.getMessage());
            }
        }
    }

    /**
     * Injects the globally overridden tooltips into the given translation map.
     * Often used when the server sends UpdateTranslations(Init).
     */
    public void injectIntoInitPacket(@Nonnull UpdateTranslations packet, @Nullable String locale) {
        if (packet.translations == null) return;
        
        if (locale == null || locale.isEmpty()) locale = "en-US";
        
        Set<String> allIds = new HashSet<>();
        allIds.addAll(additiveLines.keySet());
        allIds.addAll(replacedLines.keySet());
        
        for (String baseItemId : allIds) {
            String key = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            if (key == null || key.trim().isEmpty()) continue;
            
            String computed = getGlobalDescriptionFromMap(baseItemId, packet.translations, locale);
            if (computed != null) {
                packet.translations.put(key, computed);
            }
        }
    }

    private String getGlobalDescriptionFromMap(String baseItemId, Map<String, String> translationsMap, String fallbackLocale) {
        List<GlobalTooltipLine> replace = replacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", resolveLinesFromMap(replace, translationsMap, fallbackLocale));
        }
        
        List<GlobalTooltipLine> add = additiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String descKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            String original = translationsMap.get(descKey);
            if (original == null) {
                original = virtualItemRegistry.getOriginalDescription(baseItemId, fallbackLocale);
            }
            
            StringBuilder sb = new StringBuilder();
            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", resolveLinesFromMap(add, translationsMap, fallbackLocale)));
            return sb.toString();
        }
        
        return null;
    }

    private List<String> resolveLinesFromMap(List<GlobalTooltipLine> lines, Map<String, String> translationsMap, String fallbackLocale) {
        List<String> resolved = new ArrayList<>();
        com.hypixel.hytale.server.core.modules.i18n.I18nModule i18n = null;
        try {
            i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
        } catch (Exception ignored) {}
        
        for (GlobalTooltipLine line : lines) {
            if (line.isTranslationKey) {
                String msg = translationsMap.get(line.text);
                if (msg == null && i18n != null) {
                    msg = i18n.getMessage(fallbackLocale, line.text);
                }
                resolved.add(msg != null ? msg : line.text);
            } else {
                resolved.add(line.text);
            }
        }
        return resolved;
    }

    /**
     * Gets the full description for an item incorporating global updates and original text.
     */
    @Nullable
    public String getGlobalDescription(@Nonnull String baseItemId, @Nonnull String locale) {
        // Replace overrides completely overwrite everything else
        List<GlobalTooltipLine> replace = replacedLines.get(baseItemId);
        if (replace != null) {
            return String.join("\n", resolveLines(replace, locale));
        }
        
        List<GlobalTooltipLine> add = additiveLines.get(baseItemId);
        if (add != null && !add.isEmpty()) {
            String original = virtualItemRegistry.getOriginalDescription(baseItemId, locale);
            StringBuilder sb = new StringBuilder();
            
            if (original != null && !original.isEmpty()) {
                sb.append(original);
                sb.append("\n\n");
            }
            sb.append(String.join("\n", resolveLines(add, locale)));
            return sb.toString();
        }
        
        // If there's neither replace nor add (e.g. cleared), return the original text directly.
        return virtualItemRegistry.getOriginalDescription(baseItemId, locale);
    }

    private List<String> resolveLines(List<GlobalTooltipLine> lines, String locale) {
        List<String> resolved = new ArrayList<>();
        com.hypixel.hytale.server.core.modules.i18n.I18nModule i18n = null;
        try {
            i18n = com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
        } catch (Exception ignored) {}
        
        for (GlobalTooltipLine line : lines) {
            if (line.isTranslationKey && i18n != null) {
                String msg = i18n.getMessage(locale, line.text);
                resolved.add(msg != null ? msg : line.text);
            } else {
                resolved.add(line.text);
            }
        }
        return resolved;
    }
}
