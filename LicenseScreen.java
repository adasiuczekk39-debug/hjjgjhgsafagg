package pl.chestbot.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import pl.chestbot.Hwid;
import pl.chestbot.License;

/** Ekran wpisywania klucza licencji (pokazuje sie zamiast menu, gdy nie ma aktywnej licencji). */
public class LicenseScreen extends Screen {
    private static final int W = 340, H = 196;
    private static final int ACCENT = 0xFF4ADE80;
    private int px, py;
    private TextFieldWidget field;

    public LicenseScreen() { super(Text.literal("ChestBot - licencja")); }

    @Override
    protected void init() {
        px = (width - W) / 2;
        py = (height - H) / 2;

        field = new TextFieldWidget(textRenderer, px + 20, py + 68, W - 40, 18, Text.literal("Klucz"));
        field.setMaxLength(40);
        field.setPlaceholder(Text.literal("CB-XXXX-XXXX-XXXX-XXXX"));
        addDrawableChild(field);
        setInitialFocus(field);

        addDrawableChild(new FlatButton(px + 20, py + 96, 140, 20, "Aktywuj", b -> License.activate(field.getText())));
        addDrawableChild(new FlatButton(px + W - 160, py + 96, 140, 20, "Kopiuj ID komputera",
                b -> client.keyboard.setClipboard(Hwid.get())));
        addDrawableChild(new FlatButton(px + W - 22, py + 4, 16, 14, "X", b -> close()));
    }

    @Override
    public void tick() {
        if (License.valid()) client.setScreen(new ConfigScreen());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(px - 1, py - 1, px + W + 1, py + H + 1, ACCENT);
        ctx.fill(px, py, px + W, py + H, 0xF0101418);
        ctx.fill(px, py, px + W, py + 24, 0xFF0B0E11);
        ctx.drawTextWithShadow(textRenderer, "ChestBot", px + 16, py + 8, ACCENT);
        ctx.drawTextWithShadow(textRenderer, "licencja", px + 16 + textRenderer.getWidth("ChestBot") + 6, py + 8, 0xFF777777);

        ctx.drawTextWithShadow(textRenderer, "Wpisz klucz licencji, aby odblokować moda:", px + 20, py + 46, 0xFFFFFFFF);

        String msg = License.isChecking() ? "Sprawdzam..." : License.message();
        int color = License.valid() ? ACCENT : 0xFFFF6B6B;
        ctx.drawTextWithShadow(textRenderer, msg, px + 20, py + 128, color);

        String id = Hwid.get();
        ctx.drawTextWithShadow(textRenderer, "ID komputera: " + id.substring(0, 8) + "…", px + 20, py + 150, 0xFF777777);
        ctx.drawTextWithShadow(textRenderer, "Klucz zostaje przypisany do tego komputera.", px + 20, py + 164, 0xFF666666);
    }

    @Override
    public boolean shouldPause() { return false; }
}
