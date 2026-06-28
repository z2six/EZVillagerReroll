package org.z2six.villageroverhaul.client.render;

final class ArmorModelTransformPolicy {
    private ArmorModelTransformPolicy() {}

    enum RenderKind {
        VANILLA_HUMANOID,
        CUSTOM_HUMANOID,
        EXTENDED_PIECES,
        CUSTOM_MODEL
    }

    static boolean preserveNativeHeadPivot(RenderKind kind) {
        return kind == RenderKind.CUSTOM_HUMANOID
                || kind == RenderKind.EXTENDED_PIECES
                || kind == RenderKind.CUSTOM_MODEL;
    }

    static ArmorEditorTransform forExtendedArmorPieces(ArmorEditorTransform slot, ArmorEditorTransform part) {
        return forRenderKind(null, RenderKind.EXTENDED_PIECES, slot, part);
    }

    static ArmorEditorTransform forExtendedArmorPieces(ArmorEditorSettings.SlotKey slotKey, ArmorEditorTransform slot, ArmorEditorTransform part) {
        return forRenderKind(slotKey, RenderKind.EXTENDED_PIECES, slot, part);
    }

    static ArmorEditorTransform forWholeCustomModel(ArmorEditorSettings.SlotKey slotKey, ArmorEditorTransform slot, ArmorEditorTransform part) {
        return combine(RenderKind.CUSTOM_MODEL, slot, ArmorEditorTransform.IDENTITY);
    }

    static ArmorEditorTransform forRenderKind(RenderKind kind, ArmorEditorTransform slot, ArmorEditorTransform part) {
        return forRenderKind(null, kind, slot, part);
    }

    static ArmorEditorTransform forRenderKind(ArmorEditorSettings.SlotKey slotKey, RenderKind kind, ArmorEditorTransform slot, ArmorEditorTransform part) {
        return combine(kind == null ? RenderKind.VANILLA_HUMANOID : kind, slot, part);
    }

    private static ArmorEditorTransform combine(RenderKind kind, ArmorEditorTransform slot, ArmorEditorTransform part) {
        ArmorEditorTransform s = slot == null ? ArmorEditorTransform.IDENTITY : slot;
        ArmorEditorTransform p = part == null ? ArmorEditorTransform.IDENTITY : part;
        float sx = s.sx() * p.sx();
        float sy = s.sy() * p.sy();
        float sz = s.sz() * p.sz();

        return new ArmorEditorTransform(
                s.x() + p.x(),
                s.y() + p.y(),
                s.z() + p.z(),
                sx,
                sy,
                sz
        );
    }
}
