package com.example.fishseller;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Envanter dolunca: olta ile shift+sag tik -> menu -> "Balik Satisi" -> baliklari koy -> kapat -> oltayi tekrar at.
 * J: elle satis baslat/iptal.  K: satis modu (sadece baliklar / hotbar disindaki her sey).  L: otomatik satisi ac/kapat.
 */
public class FishSeller implements ClientModInitializer {

    private enum State { IDLE, SNEAK, RELEASE, WAIT_MENU, WAIT_SELL, FILL, CLOSE, RECAST }

    private static final String[] FISH_IDS = {
        "cod", "salmon", "tropical_fish", "pufferfish", "cooked_cod", "cooked_salmon"
    };
    private static final String[] KEYWORDS = {"balik", "fish", "agirlik"};

    private KeyBinding sellKey;
    private KeyBinding modeKey;
    private KeyBinding autoKey;

    private State state = State.IDLE;
    private int ticks = 0;
    private int sold = 0;
    private int cooldown = 0;
    private int checkTicks = 0;
    private boolean allMode = false;
    private boolean auto = true;
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        sellKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Balıkları sat (elle)", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "Balık Satış Modu"));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Satış modunu değiştir", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "Balık Satış Modu"));
        autoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Otomatik satışı aç/kapat", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, "Balık Satış Modu"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static String norm(String s) {
        s = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replace('\u0131', 'i');
    }

    private void go(State s) {
        state = s;
        ticks = 0;
    }

    private void msg(MinecraftClient c, String text) {
        if (c.player != null) c.player.sendMessage(Text.literal(text), true);
    }

    private void abort(MinecraftClient c, String reason) {
        c.options.sneakKey.setPressed(false);
        queue.clear();
        state = State.IDLE;
        ticks = 0;
        cooldown = 200;
        msg(c, reason);
    }

    private void tick(MinecraftClient c) {
        if (cooldown > 0) cooldown--;

        while (modeKey.wasPressed()) {
            allMode = !allMode;
            msg(c, allMode ? "Satış modu: hotbar dışındaki HER ŞEY" : "Satış modu: sadece balıklar");
        }
        while (autoKey.wasPressed()) {
            auto = !auto;
            msg(c, auto ? "Otomatik satış: AÇIK" : "Otomatik satış: KAPALI");
        }
        while (sellKey.wasPressed()) {
            if (state == State.IDLE) start(c);
            else abort(c, "İptal edildi");
        }

        if (state == State.IDLE) {
            autoCheck(c);
            return;
        }

        if (c.player == null || c.world == null || c.interactionManager == null) {
            state = State.IDLE;
            return;
        }
        ticks++;

        switch (state) {
            case SNEAK:
                if (ticks >= 5) {
                    c.interactionManager.interactItem(c.player, Hand.MAIN_HAND);
                    go(State.RELEASE);
                }
                break;

            case RELEASE:
                if (ticks >= 2) {
                    c.options.sneakKey.setPressed(false);
                    go(State.WAIT_MENU);
                }
                break;

            case WAIT_MENU: {
                if (ticks > 80) { abort(c, "Menü açılmadı"); return; }
                HandledScreen<?> hs = screen(c);
                if (hs == null) return;
                if (isSellScreen(hs)) { go(State.FILL); return; }
                if (ticks < 4) return;
                Slot btn = findButton(c, hs);
                if (btn == null) {
                    dumpSlots(c, hs);
                    abort(c, "Satış butonu bulunamadı (sohbete slot listesi yazıldı)");
                    return;
                }
                c.interactionManager.clickSlot(hs.getScreenHandler().syncId, btn.id, 0, SlotActionType.PICKUP, c.player);
                go(State.WAIT_SELL);
                break;
            }

            case WAIT_SELL: {
                if (ticks > 80) { abort(c, "Satış menüsü açılmadı"); return; }
                HandledScreen<?> hs = screen(c);
                if (hs != null && isSellScreen(hs)) go(State.FILL);
                break;
            }

            case FILL: {
                HandledScreen<?> hs = screen(c);
                if (hs == null || !isSellScreen(hs)) { abort(c, "Satış menüsü kapandı"); return; }
                if (ticks < 5) return;
                if (ticks == 5) {
                    buildQueue(c, hs);
                    return;
                }
                if (ticks % 2 == 0) {
                    if (queue.isEmpty()) {
                        go(State.CLOSE);
                        return;
                    }
                    int id = queue.poll();
                    Slot s = hs.getScreenHandler().slots.get(id);
                    if (s.hasStack()) {
                        c.interactionManager.clickSlot(hs.getScreenHandler().syncId, s.id, 0, SlotActionType.QUICK_MOVE, c.player);
                        sold++;
                    }
                }
                break;
            }

            case CLOSE:
                if (ticks >= 8) {
                    c.player.closeHandledScreen();
                    msg(c, sold > 0 ? "Satıldı: " + sold + " stack" : "Satılacak balık bulunamadı (K ile modu değiştir)");
                    go(State.RECAST);
                }
                break;

            case RECAST:
                if (ticks >= 12) {
                    if (c.currentScreen == null
                        && c.player.getInventory().getMainHandStack().isOf(Items.FISHING_ROD)
                        && !hasBobber(c)) {
                        c.interactionManager.interactItem(c.player, Hand.MAIN_HAND);
                    }
                    state = State.IDLE;
                    ticks = 0;
                    cooldown = 200;
                }
                break;

            default:
                break;
        }
    }

    private void autoCheck(MinecraftClient c) {
        if (!auto || cooldown > 0) return;
        if (c.player == null || c.world == null || c.currentScreen != null) return;
        if (++checkTicks < 20) return;
        checkTicks = 0;
        if (!inventoryFull(c)) return;
        if (countCandidates(c) == 0) {
            cooldown = 600;
            msg(c, "Envanter dolu ama satılacak balık bulunamadı (K ile modu değiştir)");
            return;
        }
        start(c);
    }

    private boolean inventoryFull(MinecraftClient c) {
        var inv = c.player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private int countCandidates(MinecraftClient c) {
        var inv = c.player.getInventory();
        int n = 0;
        for (int i = 9; i < 36; i++) {
            if (isCandidate(c, inv.getStack(i))) n++;
        }
        return n;
    }

    private boolean hasBobber(MinecraftClient c) {
        for (Entity e : c.world.getEntities()) {
            if (e instanceof FishingBobberEntity b && b.getPlayerOwner() == c.player) return true;
        }
        return false;
    }

    private void start(MinecraftClient c) {
        if (c.player == null || c.world == null) return;
        if (c.currentScreen != null) return;
        var inv = c.player.getInventory();
        if (!inv.getMainHandStack().isOf(Items.FISHING_ROD)) {
            int found = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.FISHING_ROD)) { found = i; break; }
            }
            if (found < 0) {
                cooldown = 200;
                msg(c, "Hotbar'da olta yok");
                return;
            }
            inv.selectedSlot = found;
        }
        sold = 0;
        queue.clear();
        c.options.sneakKey.setPressed(true);
        go(State.SNEAK);
    }

    private HandledScreen<?> screen(MinecraftClient c) {
        return c.currentScreen instanceof HandledScreen<?> hs ? hs : null;
    }

    private boolean isSellScreen(HandledScreen<?> hs) {
        return norm(hs.getTitle().getString()).contains("balik satis");
    }

    private Slot findButton(MinecraftClient c, HandledScreen<?> hs) {
        for (Slot s : hs.getScreenHandler().slots) {
            if (s.inventory == c.player.getInventory()) continue;
            if (!s.hasStack()) continue;
            String n = norm(s.getStack().getName().getString());
            if (n.contains("balik") && n.contains("sat")) return s;
        }
        return null;
    }

    private void dumpSlots(MinecraftClient c, HandledScreen<?> hs) {
        int shown = 0;
        for (Slot s : hs.getScreenHandler().slots) {
            if (s.inventory == c.player.getInventory()) continue;
            if (!s.hasStack()) continue;
            if (shown++ >= 25) break;
            c.player.sendMessage(Text.literal("slot " + s.id + ": " + s.getStack().getName().getString()), false);
        }
    }

    private void buildQueue(MinecraftClient c, HandledScreen<?> hs) {
        queue.clear();
        for (Slot s : hs.getScreenHandler().slots) {
            if (s.inventory != c.player.getInventory()) continue;
            int idx = s.getIndex();
            if (idx < 9 || idx > 35) continue;
            if (isCandidate(c, s.getStack())) queue.add(s.id);
        }
    }

    private boolean isCandidate(MinecraftClient c, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isOf(Items.FISHING_ROD)) return false;
        if (allMode) return true;

        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        for (String f : FISH_IDS) if (f.equals(id)) return true;

        StringBuilder sb = new StringBuilder();
        for (Text t : stack.getTooltip(c.player, TooltipContext.Default.BASIC)) {
            sb.append(t.getString()).append(' ');
        }
        String n = norm(sb.toString());
        for (String k : KEYWORDS) if (n.contains(k)) return true;
        return false;
    }
}
