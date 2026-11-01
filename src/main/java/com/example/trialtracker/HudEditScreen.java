package com.example.trialtracker;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

// Экран, где счётчик можно перетащить мышью
public class HudEditScreen extends Screen {
    private static final int W = 172, H = 54;
    private final TrialTrackerClient mod;
    private boolean dragging;
    private int offX, offY;

    public HudEditScreen(TrialTrackerClient mod) {
        super(Text.translatable("key.trialtracker.hud_edit"));
        this.mod = mod;
    }

    private int curX() { return mod.hudX; }
    private int curY() { return mod.hudY < 0 ? this.height - 46 : mod.hudY; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("trialtracker.hud.hint"),
                this.width / 2, 20, 0xFFFFFFFF);
        int x = curX(), y = curY();
        ctx.drawBorder(x - 3, y - 3, W, H + 4, 0xFFFFFFFF);
        mod.drawHud(ctx, x, y, 2, 4, 1, 3);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = curX(), y = curY();
        if (button == 0 && mx >= x - 3 && mx <= x - 3 + W && my >= y - 3 && my <= y - 3 + H + 4) {
            dragging = true;
            offX = (int) mx - x;
            offY = (int) my - y;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            mod.hudX = Math.max(3, Math.min(this.width - W, (int) mx - offX));
            mod.hudY = Math.max(3, Math.min(this.height - H, (int) my - offY));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            mod.hudX = 6;
            mod.hudY = -1;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        mod.saveConfig();
    }
}
