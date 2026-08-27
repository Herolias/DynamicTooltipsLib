package org.herolias.tooltips.internal;

import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.herolias.tooltips.api.ItemVisualOverrides;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free compatibility checks for the CustomPage BSON interception paths.
 */
public final class TooltipPacketAdapterCompatibilityTest {

    private static final String HASH = "deadbeef";

    private final TooltipPacketAdapter adapter = new TooltipPacketAdapter(
            new StubVirtualItemRegistry(), new StubTooltipRegistry(), null);

    private final Method processMethod;

    private TooltipPacketAdapterCompatibilityTest() throws ReflectiveOperationException {
        processMethod = TooltipPacketAdapter.class.getDeclaredMethod(
                "processCustomUICommandData",
                UUID.class,
                String.class,
                String.class,
                String.class,
                Map.class,
                Map.class);
        processMethod.setAccessible(true);
    }

    public static void main(String[] args) throws Exception {
        TooltipPacketAdapterCompatibilityTest test = new TooltipPacketAdapterCompatibilityTest();
        test.virtualizesItemIdAtAnyRootIndex();
        test.doesNotVirtualizeAlreadyVirtualItemId();
        test.virtualizesDirectItemStackAndPreservesStackQuality();
        test.appliesQualityOverrideToDirectItemStack();
        test.virtualizesWrappedGridSlotAndRemovesMetadata();
        test.ignoresUnrelatedMinimalIdDocument();
        test.virtualizesMinimalDocumentOnlyForItemStackSelector();
    }

    private void virtualizesItemIdAtAnyRootIndex() throws Exception {
        BsonDocument result = process("#Ring.ItemId", "{\"7\":\"Item_A\"}");
        require(result != null, "ItemId command should be modified");
        require(virtualId("Item_A").equals(result.getString("7").getValue()),
                "ItemId at a non-zero BSON key was not virtualized");
    }

    private void doesNotVirtualizeAlreadyVirtualItemId() throws Exception {
        BsonDocument result = process("#Ring.ItemId", "{\"0\":\"Item_A__dtt_deadbeef\"}");
        require(result == null, "An already virtual ItemId must not be virtualized again");
    }

    private void virtualizesDirectItemStackAndPreservesStackQuality() throws Exception {
        BsonDocument result = process(
                "#Ring.ItemStack",
                "{\"0\":{\"Id\":\"Item_A\",\"Quantity\":1,\"Quality\":2,\"Metadata\":{\"Roll\":4}}}");
        BsonDocument item = requireDocument(result, "0");
        require(virtualId("Item_A").equals(item.getString("Id").getValue()),
                "Direct ItemStack ID was not virtualized");
        require(item.getInt32("Quality").getValue() == 2,
                "A stack-specific quality must survive when no visual quality override exists");
        require(!item.containsKey("Metadata"), "Custom UI metadata must be stripped");
    }

    private void appliesQualityOverrideToDirectItemStack() throws Exception {
        BsonDocument result = process(
                "#Ring.ItemStack",
                "{\"0\":{\"Id\":\"Item_Quality\",\"Quantity\":1,\"Quality\":2}}");
        BsonDocument item = requireDocument(result, "0");
        require(item.getInt32("Quality").getValue() == 9,
                "Visual quality override was not copied to the Update 6 ItemStack payload");
    }

    private void virtualizesWrappedGridSlotAndRemovesMetadata() throws Exception {
        BsonDocument result = process(
                "#Grid.Slots",
                "{\"0\":[{\"ItemStack\":{\"ItemId\":\"Item_A\",\"Quantity\":1,\"Metadata\":{\"Roll\":4}}}]}" );
        require(result != null, "Wrapped ItemGrid slot should be modified");
        BsonArray slots = result.getArray("0");
        BsonDocument item = slots.get(0).asDocument().getDocument("ItemStack");
        require(virtualId("Item_A").equals(item.getString("ItemId").getValue()),
                "Wrapped ItemGrid slot was not virtualized");
        require(!item.containsKey("Metadata"), "Wrapped ItemGrid metadata must be stripped");
    }

    private void ignoresUnrelatedMinimalIdDocument() throws Exception {
        BsonDocument result = process("#Panel.Config", "{\"0\":{\"Id\":\"Item_A\",\"Label\":\"Example\"}}");
        require(result == null, "An unrelated document with an Id field must not be treated as an ItemStack");
    }

    private void virtualizesMinimalDocumentOnlyForItemStackSelector() throws Exception {
        BsonDocument result = process("#Ring.ItemStack", "{\"0\":{\"Id\":\"Item_A\"}}");
        BsonDocument item = requireDocument(result, "0");
        require(virtualId("Item_A").equals(item.getString("Id").getValue()),
                "Minimal ItemStack document was not virtualized for an ItemStack selector");
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private BsonDocument process(String selector, String data) throws Exception {
        Map<String, ItemBase> items = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();
        String result = (String) processMethod.invoke(
                adapter, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "en-US", selector, data, items, translations);
        return result != null ? BsonDocument.parse(result) : null;
    }

    private static BsonDocument requireDocument(@Nullable BsonDocument root, String key) {
        require(root != null, "Expected command data to be modified");
        BsonValue value = root.get(key);
        require(value != null && value.isDocument(), "Expected BSON document at key " + key);
        return value.asDocument();
    }

    private static String virtualId(String itemId) {
        return itemId + VirtualItemRegistry.VIRTUAL_SEPARATOR + HASH;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class StubTooltipRegistry extends TooltipRegistry {
        @Override
        @Nullable
        public ComposedTooltip compose(
                @Nonnull String itemId,
                @Nullable String metadata,
                @Nullable String locale) {
            if (VirtualItemRegistry.isVirtualId(itemId) || !itemId.startsWith("Item_")) {
                return null;
            }
            ItemVisualOverrides overrides = "Item_Quality".equals(itemId)
                    ? ItemVisualOverrides.builder().qualityIndex(9).build()
                    : null;
            return new ComposedTooltip(
                    Collections.singletonList("Dynamic line"),
                    null,
                    null,
                    null,
                    null,
                    overrides,
                    HASH);
        }
    }

    private static final class StubVirtualItemRegistry extends VirtualItemRegistry {
        @Override
        @Nullable
        public ItemBase getOrCreateVirtualItemBase(
                @Nonnull String baseItemId,
                @Nonnull String virtualId,
                @Nullable String nameOverride,
                @Nullable ItemVisualOverrides visualOverrides,
                @Nullable String nameTranslationKey,
                @Nullable String descriptionTranslationKey) {
            ItemBase item = new ItemBase();
            item.id = virtualId;
            item.durability = 100;
            item.qualityIndex = visualOverrides != null && visualOverrides.getQualityIndex() != null
                    ? visualOverrides.getQualityIndex()
                    : 3;
            item.translationProperties = new ItemTranslationProperties();
            item.translationProperties.description = getVirtualDescriptionKey(virtualId);
            return item;
        }

        @Override
        @Nonnull
        public String getOriginalDescription(@Nonnull String baseItemId, @Nullable String language) {
            return "Original description";
        }
    }
}
