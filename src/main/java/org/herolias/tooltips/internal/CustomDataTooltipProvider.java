package org.herolias.tooltips.internal;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.herolias.tooltips.api.CustomTooltipKeys;
import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipPriority;
import org.herolias.tooltips.api.TooltipProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Built-in {@link TooltipProvider} that reads standard metadata keys
 * ({@link CustomTooltipKeys#CUSTOM_NAME} and {@link CustomTooltipKeys#CUSTOM_LINES})
 * and converts them into tooltip data.
 * <p>
 * This allows mods to add custom names or extra lines to any item simply by
 * writing to its metadata, without implementing a full {@code TooltipProvider}.
 */
public class CustomDataTooltipProvider implements TooltipProvider {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String PROVIDER_ID = "dynamic-tooltips-lib:custom-data";

    @Nonnull
    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public int getPriority() {
        return TooltipPriority.LAST;
    }

    @Nullable
    @Override
    public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata) {
        if (metadata == null || metadata.isEmpty()) return null;

        // Quick string-contains checks to avoid BSON parsing when keys aren't present
        boolean hasName = metadata.contains("\"" + CustomTooltipKeys.CUSTOM_NAME + "\"");
        boolean hasLines = metadata.contains("\"" + CustomTooltipKeys.CUSTOM_LINES + "\"");
        if (!hasName && !hasLines) return null;

        try {
            BsonDocument doc = BsonDocument.parse(metadata);

            String nameOverride = null;
            List<String> lines = null;
            StringBuilder hashBuilder = new StringBuilder();

            // ── Custom name ──
            if (hasName) {
                BsonValue nameValue = doc.get(CustomTooltipKeys.CUSTOM_NAME);
                if (nameValue != null && nameValue.isString()) {
                    nameOverride = nameValue.asString().getValue();
                    hashBuilder.append("n:").append(nameOverride);
                }
            }

            // ── Custom lines ──
            if (hasLines) {
                BsonValue linesValue = doc.get(CustomTooltipKeys.CUSTOM_LINES);
                if (linesValue != null && linesValue.isArray()) {
                    BsonArray arr = linesValue.asArray();
                    lines = new ArrayList<>(arr.size());
                    for (BsonValue element : arr) {
                        if (element.isString()) {
                            String line = element.asString().getValue();
                            lines.add(line);
                            hashBuilder.append("l:").append(line).append(';');
                        }
                    }
                    if (lines.isEmpty()) lines = null;
                }
            }

            if (nameOverride == null && lines == null) return null;

            TooltipData.Builder builder = TooltipData.builder()
                    .hashInput(hashBuilder.toString());

            if (nameOverride != null) {
                builder.nameOverride(nameOverride);
            }
            if (lines != null) {
                builder.addLines(lines);
            }

            return builder.build();

        } catch (Exception e) {
            LOGGER.atFine().log("Failed to parse custom tooltip data for " + itemId + ": " + e.getMessage());
            return null;
        }
    }
}
