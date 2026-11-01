package com.example.trialtracker;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

// Меню выбора: что подсвечивать
public class HighlightScreen extends Screen {
    private final TrialTrackerClient mod;
    private ButtonWidget spawnersBtn, vaultsBtn, tracerBtn, distanceBtn;

    public HighlightScreen(TrialTrackerClient mod) {
        super(Text.translatable("trialtracker.menu.title"));
        this.mod = mod;
    }

    private static Text state(boolean on) {
        return Text.translatable(on ? "trialtracker.on" : "trialtracker.off");
    }

    private void refresh() {
        spawnersBtn.setMessage(Text.translatable("trialtracker.menu.spawners", state(mod.showSpawners)));
        vaultsBtn.setMessage(Text.translatable("trialtracker.menu.vaults", state(mod.showVaults)));
        tracerBtn.setMessage(Text.translatable("trialtracker.menu.tracer", state(mod.showTracer)));
        distanceBtn.setMessage(Text.translatable("trialtracker.menu.distance", state(mod.showDistance)));
    }

    @Override
    protected void init() {
        int w = 200, x = this.width / 2 - w / 2, y = this.height / 2 - 50;
        spawnersBtn = ButtonWidget.builder(Text.empty(), b -> { mod.showSpawners = !mod.showSpawners; refresh(); })
                .dimensions(x, y, w, 20).build();
        vaultsBtn = ButtonWidget.builder(Text.empty(), b -> { mod.showVaults = !mod.showVaults; refresh(); })
                .dimensions(x, y + 24, w, 20).build();
        tracerBtn = ButtonWidget.builder(Text.empty(), b -> { mod.showTracer = !mod.showTracer; refresh(); })
                .dimensions(x, y + 48, w, 20).build();
        distanceBtn = ButtonWidget.builder(Text.empty(), b -> { mod.showDistance = !mod.showDistance; refresh(); })
                .dimensions(x, y + 72, w, 20).build();
        ButtonWidget done = ButtonWidget.builder(Text.translatable("trialtracker.menu.done"), b -> this.close())
                .dimensions(x, y + 102, w, 20).build();
        addDrawableChild(spawnersBtn);
        addDrawableChild(vaultsBtn);
        addDrawableChild(tracerBtn);
        addDrawableChild(distanceBtn);
        addDrawableChild(done);
        refresh();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Повторное нажатие бинда закрывает меню
        if (mod.toggleKey.matchesKey(keyCode, scanCode)) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        mod.saveConfig();
    }
}
