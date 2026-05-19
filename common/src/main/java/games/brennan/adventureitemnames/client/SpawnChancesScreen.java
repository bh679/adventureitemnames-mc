package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.ChanceKind;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 — top-level Spawn Chances editor. Four rows, one per
 * {@link ChanceKind}, each with a dual-bound slider + text edit-box and
 * a {@code ↺} reset button. A footer button opens
 * {@link SelectorsScreen} for per-selector chain remapping.
 *
 * <p>Edits go into the shared {@link EditBuffer} and surface in the
 * gated preview panel at the bottom. {@code Save to pack} flushes
 * everything through {@link ConfigSave#commit}.
 */
@Environment(EnvType.CLIENT)
public final class SpawnChancesScreen extends Screen {

    private static final int ROW_H = 26;
    private static final int LABEL_W = 160;
    private static final int SLIDER_W = 160;
    private static final int EDIT_W = 52;
    private static final int RESET_W = 22;
    private static final int FIRST_ROW_Y = 48;

    private final Screen parent;
    private final EditBuffer buffer;
    private final List<Row> rows = new ArrayList<>();
    private PreviewPanel preview;
    private Button saveButton;

    public SpawnChancesScreen(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.spawn_chances.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    @Override
    protected void init() {
        rows.clear();
        ChanceKind[] kinds = ChanceKind.values();
        int rowLeft = width / 2 - (LABEL_W + SLIDER_W + EDIT_W + RESET_W + 12) / 2;
        for (int i = 0; i < kinds.length; i++) {
            int y = FIRST_ROW_Y + i * ROW_H;
            rows.add(createRow(kinds[i], rowLeft, y));
        }

        int selBtnY = FIRST_ROW_Y + kinds.length * ROW_H + 6;
        addRenderableWidget(Button.builder(
            Component.translatable("screen.adventureitemnames.spawn_chances.configure_selectors"),
            b -> Minecraft.getInstance().setScreen(new SelectorsScreen(this, buffer))
        ).bounds(width / 2 - 100, selBtnY, 200, 20).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.HEIGHT - 26, 80, 20).build());

        saveButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.save"),
            b -> save()
        ).bounds(width - 88, height - PreviewPanel.HEIGHT - 26, 80, 20).build();
        saveButton.active = buffer.isDirty();
        addRenderableWidget(saveButton);

        preview = new PreviewPanel(buffer, null, true);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
    }

    private Row createRow(ChanceKind kind, int rowLeft, int y) {
        Row row = new Row(kind);
        int x = rowLeft + LABEL_W + 4;
        row.slider = new ChanceSlider(x, y, SLIDER_W, 18, kind, buffer.effectiveChance(kind), (newValue) -> {
            buffer.setChance(kind, newValue);
            row.setEditBoxValue(formatChance(newValue));
            refreshSaveAndPreview();
        });
        addRenderableWidget(row.slider);
        x += SLIDER_W + 4;

        row.editBox = new EditBox(font, x, y, EDIT_W, 18, Component.literal(kind.key()));
        row.editBox.setMaxLength(5);
        row.editBox.setValue(formatChance(buffer.effectiveChance(kind)));
        row.editBox.setResponder(text -> {
            if (row.suppressEditResponder) return;
            Float parsed = tryParseChance(text);
            if (parsed == null) return;
            buffer.setChance(kind, parsed);
            row.slider.setSliderValue(parsed);
            refreshSaveAndPreview();
        });
        addRenderableWidget(row.editBox);
        x += EDIT_W + 4;

        row.resetButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.reset"),
            b -> resetRow(row)
        ).bounds(x, y, RESET_W, 18).build();
        addRenderableWidget(row.resetButton);

        return row;
    }

    private void resetRow(Row row) {
        buffer.clearChance(row.kind);
        float def = row.kind.defaultValue();
        row.slider.setSliderValue(def);
        row.setEditBoxValue(formatChance(def));
        refreshSaveAndPreview();
    }

    private void refreshSaveAndPreview() {
        if (saveButton != null) saveButton.active = buffer.isDirty();
        if (preview != null) preview.rerollNow();
    }

    private void save() {
        ConfigSave.commit(buffer, () -> {
            for (Row row : rows) {
                float v = buffer.effectiveChance(row.kind);
                row.slider.setSliderValue(v);
                row.setEditBoxValue(formatChance(v));
            }
            if (saveButton != null) saveButton.active = false;
            if (preview != null) preview.rerollNow();
        });
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);

        int rowLeft = width / 2 - (LABEL_W + SLIDER_W + EDIT_W + RESET_W + 12) / 2;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = FIRST_ROW_Y + i * ROW_H + 5;
            gfx.drawString(font, Component.translatable(labelKey(row.kind)),
                rowLeft, y, 0xFFE0E0E0, false);
        }

        if (saveButton != null) saveButton.active = buffer.isDirty();
        if (preview != null) preview.render(gfx, mouseX, mouseY, partial);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static String labelKey(ChanceKind kind) {
        return "screen.adventureitemnames.spawn_chances." + kind.key();
    }

    private static String formatChance(float v) {
        return String.format("%.2f", clamp01(v));
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** Returns null when {@code text} is not a parseable {@code [0,1]} number. */
    private static Float tryParseChance(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            float v = Float.parseFloat(text.trim());
            if (Float.isNaN(v) || v < 0f || v > 1f) return null;
            return v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** One UI row's tightly-coupled widgets. */
    private static final class Row {
        final ChanceKind kind;
        ChanceSlider slider;
        EditBox editBox;
        Button resetButton;
        boolean suppressEditResponder;

        Row(ChanceKind kind) { this.kind = kind; }

        /** Set the edit-box without re-firing its responder — avoids feedback loops with the slider. */
        void setEditBoxValue(String text) {
            if (editBox == null) return;
            suppressEditResponder = true;
            try {
                editBox.setValue(text);
            } finally {
                suppressEditResponder = false;
            }
        }
    }

    /**
     * Vanilla {@link AbstractSliderButton} subclass mapped 1:1 to a
     * float in {@code [0, 1]}. {@link #setSliderValue} updates the
     * visual position without firing the {@link #applyValue} callback —
     * lets the text-edit responder drive the slider without bouncing
     * through the slider's own change channel.
     */
    private static final class ChanceSlider extends AbstractSliderButton {

        private final ChanceKind kind;
        private final java.util.function.Consumer<Float> onChange;

        ChanceSlider(int x, int y, int w, int h, ChanceKind kind, float initial,
                     java.util.function.Consumer<Float> onChange) {
            super(x, y, w, h, Component.literal(""), clamp01(initial));
            this.kind = kind;
            this.onChange = onChange;
            updateMessage();
        }

        void setSliderValue(double newValue) {
            this.value = clamp01((float) newValue);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(formatChance((float) value)));
        }

        @Override
        protected void applyValue() {
            if (onChange != null) onChange.accept((float) value);
        }
    }
}
