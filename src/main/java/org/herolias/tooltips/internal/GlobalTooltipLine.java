package org.herolias.tooltips.internal;

import javax.annotation.Nonnull;

public class GlobalTooltipLine {
    public final String text;
    public final boolean isTranslationKey;

    public GlobalTooltipLine(@Nonnull String text, boolean isTranslationKey) {
        this.text = text;
        this.isTranslationKey = isTranslationKey;
    }
}
