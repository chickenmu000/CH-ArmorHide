package com.ch.armorhide;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 방어구 보호
 *  1) 사망 시: 방어구를 드롭에서 빼 두었다가 리스폰 후 같은 칸에 돌려줌
 *  2) 평소: 입고 있던 방어구가 정상적인 이유 없이 사라지면 바로 되돌림
 */
public class ArmorGuard implements Listener {

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final CHArmorHide plugin;

    // 사망 후 돌려받을 방어구 (재접속/재시작에도 유지)
    private final Map<UUID, Map<EquipmentSlot, ItemStack>> pending = new HashMap<>();
    private final File pendingFile;

    // 정상으로 인정하는 시간 (서버 틱 기준)
    private final Map<UUID, Long> legitUntil = new HashMap<>();
    private long globalLegitUntil = -1L;

    // 복구 무한 반복 방지: {횟수, 시작 틱}
    private final Map<UUID, long[]> restoreCount = new HashMap<>();

    // 원인 추적용 기록
    private final Map<UUID, String> lastCommand = new HashMap<>();
    private final Map<UUID, Long> lastCommandTick = new HashMap<>();
    private final Map<UUID, String> lastClick = new HashMap<>();
    private final Map<UUID, Long> lastClickTick = new HashMap<>();

    public ArmorGuard(CHArmorHide plugin) {
        this.plugin = plugin;
        this.pendingFile = new File(plugin.getDataFolder(), "pending.yml");
    }

    // ------------------------------------------------------------------
    // 유틸
    // ------------------------------------------------------------------
    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType() == Material.AIR || it.getAmount() <= 0;
    }

    private static String slotName(EquipmentSlot s) {
        switch (s) {
            case HEAD: return "투구";
            case CHEST: return "갑옷";
            case LEGS: return "바지";
            case FEET: return "신발";
            default: return s.name();
        }
    }

    private int window() {
        return Math.max(1, plugin.getConfig().getInt("legit-window-ticks", 5));
    }

    public void markLegit(Player p, int ticks) {
        legitUntil.put(p.getUniqueId(), (long) Bukkit.getCurrentTick() + ticks);
    }

    private boolean isLegit(Player p) {
        long now = Bukkit.getCurrentTick();
        if (now <= globalLegitUntil) {
            return true;
        }
        Long until = legitUntil.get(p.getUniqueId());
        return until != null && now <= until;
    }

    /** 인벤토리(보관칸 + 왼손) 와 커서에 같은 아이템이 있으면 true (옮겨진 것이므로 복구하지 않음) */
    private boolean hasSimilar(Player p, ItemStack item) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getStorageContents()) {
            if (!isEmpty(s) && s.isSimilar(item)) {
                return true;
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (!isEmpty(off) && off.isSimilar(item)) {
            return true;
        }
        ItemStack cursor = p.getItemOnCursor();
        return !isEmpty(cursor) && cursor.isSimilar(item);
    }

    private String context(Player p) {
        long now = Bukkit.getCurrentTick();
        StringBuilder sb = new StringBuilder();
        try {
            InventoryView v = p.getOpenInventory();
            String title = PlainTextComponentSerializer.plainText().serialize(v.title());
            sb.append("열린창=").append(v.getType().name()).append("[").append(title).append("]");
        } catch (Throwable t) {
            sb.append("열린창=?");
        }
        Long ct = lastCommandTick.get(p.getUniqueId());
        if (ct != null && now - ct <= 200) {
            sb.append(" / 직전명령어=/").append(lastCommand.get(p.getUniqueId())).append(" (").append(now - ct).append("틱 전)");
        }
        Long kt = lastClickTick.get(p.getUniqueId());
        if (kt != null && now - kt <= 200) {
            sb.append(" / 직전클릭=").append(lastClick.get(p.getUniqueId())).append(" (").append(now - kt).append("틱 전)");
        }
        return sb.toString();
    }

    private void record(String line) {
        if (!plugin.getConfig().getBoolean("log-restores", true)) {
            return;
        }
        try {
            File f = new File(plugin.getDataFolder(), "restore.log");
            plugin.getDataFolder().mkdirs();
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            Files.writeString(f.toPath(), "[" + ts + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("restore.log 기록 실패: " + e.getMessage());
        }
    }

    private void notifyOps(String msg) {
        plugin.getLogger().info(msg);
        if (!plugin.getConfig().getBoolean("notify-ops", true)) {
            return;
        }
        for (Player op : Bukkit.getOnlinePlayers()) {
            if (op.isOp()) {
                op.sendMessage("§e[ArmorHide] " + msg);
            }
        }
    }

    // ------------------------------------------------------------------
    // 사망 시 방어구 유지
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.getConfig().getBoolean("keep-on-death", true)) {
            return;
        }
        // 인벤토리가 통째로 유지되는 사망이면 방어구도 그대로 남으므로 할 일 없음
        if (e.getKeepInventory()) {
            return;
        }
        Player p = e.getEntity();
        PlayerInventory inv = p.getInventory();
        List<ItemStack> drops = e.getDrops();
        List<ItemStack> keep = e.getItemsToKeep();
        boolean respectCurse = plugin.getConfig().getBoolean("respect-vanishing-curse", true);

        Map<EquipmentSlot, ItemStack> saved = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : ARMOR) {
            ItemStack it = inv.getItem(slot);
            if (isEmpty(it)) {
                continue;
            }
            if (respectCurse && it.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                continue;
            }
            // 다른 플러그인(CH-TradeLock 의 거래불가 보호 등)이 이미 돌려주도록 지정한 것은 건드리지 않음
            boolean alreadyKept = false;
            for (ItemStack k : keep) {
                if (!isEmpty(k) && k.isSimilar(it)) {
                    alreadyKept = true;
                    break;
                }
            }
            if (alreadyKept) {
                continue;
            }
            // 드롭 목록에 실제로 들어 있는 경우만 빼서 저장 (다른 플러그인이 일부러 지운 것은 복사 방지를 위해 건드리지 않음)
            if (removeFromDrops(drops, it)) {
                saved.put(slot, it.clone());
            }
        }
        if (!saved.isEmpty()) {
            pending.put(p.getUniqueId(), saved);
            savePending();
            record(p.getName() + " 사망: 방어구 " + saved.size() + "개 보관 (리스폰 후 돌려줌)");
        }
    }

    private boolean removeFromDrops(List<ItemStack> drops, ItemStack it) {
        for (int i = 0; i < drops.size(); i++) {
            ItemStack d = drops.get(i);
            if (!isEmpty(d) && d.getAmount() == it.getAmount() && d.isSimilar(it)) {
                drops.remove(i);
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> restorePending(p), 2L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> restorePending(p), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        legitUntil.remove(u);
        restoreCount.remove(u);
        lastCommand.remove(u);
        lastCommandTick.remove(u);
        lastClick.remove(u);
        lastClickTick.remove(u);
    }

    private void restorePending(Player p) {
        if (!p.isOnline() || p.isDead()) {
            return;
        }
        Map<EquipmentSlot, ItemStack> saved = pending.remove(p.getUniqueId());
        if (saved == null) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        markLegit(p, 10);
        for (Map.Entry<EquipmentSlot, ItemStack> en : saved.entrySet()) {
            ItemStack cur = inv.getItem(en.getKey());
            if (isEmpty(cur)) {
                inv.setItem(en.getKey(), en.getValue());
            } else {
                // 그 칸에 이미 뭔가 입고 있으면 인벤토리로, 가득 차면 발밑에 떨어뜨림
                Map<Integer, ItemStack> left = inv.addItem(en.getValue());
                for (ItemStack l : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), l);
                }
            }
        }
        savePending();
        record(p.getName() + " 방어구 " + saved.size() + "개 돌려줌 (사망 보관분)");
        p.sendMessage("§a[방어구] 사망 전에 입고 있던 방어구를 돌려받았습니다.");
    }

    // ------------------------------------------------------------------
    // 평소 소실 복구
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent e) {
        if (!plugin.getConfig().getBoolean("guard-enabled", true)) {
            return;
        }
        ItemStack old = e.getOldItem();
        ItemStack now = e.getNewItem();
        if (isEmpty(old) || !isEmpty(now)) {
            return; // 입거나 바뀐 것은 무시, "입고 있던 것 → 빈칸" 만 확인
        }
        Player p = e.getPlayer();
        if (p.isDead()) {
            return;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return;
        }
        if (isLegit(p)) {
            return;
        }
        EquipmentSlot slot;
        try {
            slot = EquipmentSlot.valueOf(e.getSlotType().name());
        } catch (IllegalArgumentException ex) {
            return;
        }
        ItemStack oldClone = old.clone();
        String ctx = context(p);
        // 이벤트 안에서 바로 건드리지 않고 다음 틱에 다시 확인 후 복구
        Bukkit.getScheduler().runTask(plugin, () -> verifyAndRestore(p, slot, oldClone, ctx));
    }

    private void verifyAndRestore(Player p, EquipmentSlot slot, ItemStack old, String ctx) {
        if (!p.isOnline() || p.isDead()) {
            return;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return;
        }
        if (isLegit(p)) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        if (!isEmpty(inv.getItem(slot))) {
            return; // 이미 다시 채워짐
        }
        if (hasSimilar(p, old)) {
            return; // 인벤토리 안으로 옮겨진 것 (복사 방지)
        }
        if (pending.containsKey(p.getUniqueId())) {
            return; // 사망 보관분이 곧 돌아옴
        }
        // 무한 반복 방지: 10초(200틱) 안에 5번 넘게 복구해야 하면 다른 플러그인과 충돌 중이므로 중단
        long tick = Bukkit.getCurrentTick();
        long[] rc = restoreCount.computeIfAbsent(p.getUniqueId(), k -> new long[]{0, tick});
        if (tick - rc[1] > 200) {
            rc[0] = 0;
            rc[1] = tick;
        }
        if (rc[0] >= 5) {
            if (rc[0] == 5) {
                rc[0]++;
                notifyOps(p.getName() + " 의 " + slotName(slot) + " 복구가 반복되어 잠시 중단합니다 (다른 플러그인과 충돌 가능성) / " + ctx);
            }
            return;
        }
        rc[0]++;

        markLegit(p, 2);
        inv.setItem(slot, old);
        String line = p.getName() + " " + slotName(slot) + " 복구 " + old.getType().name() + " / " + ctx;
        record(line);
        notifyOps(line);
    }

    // ------------------------------------------------------------------
    // 정상적인 소실로 인정할 상황
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent e) {
        markLegit(e.getPlayer(), window());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        long tick = Bukkit.getCurrentTick();
        lastClickTick.put(p.getUniqueId(), tick);
        lastClick.put(p.getUniqueId(), "slot=" + e.getRawSlot() + " " + e.getClick().name() + " " + e.getAction().name());

        // 내 방어구 칸을 직접 누른 경우 (벗기 / 교체 / 숫자키 / 쉬프트클릭)
        if (e.getSlotType() == InventoryType.SlotType.ARMOR) {
            markLegit(p, window());
        }
        // 더블클릭으로 모으기도 방어구 칸에서 가져올 수 있음
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getClick() == ClickType.DOUBLE_CLICK) {
            markLegit(p, window());
        }
        // 관리자가 /invsee 등으로 다른 사람의 인벤토리를 직접 수정하는 경우
        Inventory clicked = e.getClickedInventory();
        if (clicked != null && clicked.getHolder() instanceof Player) {
            Player target = (Player) clicked.getHolder();
            if (!target.getUniqueId().equals(p.getUniqueId())) {
                markLegit(target, window());
            }
        }
    }

    private boolean isLegitCommand(String raw) {
        String c = raw.startsWith("/") ? raw.substring(1) : raw;
        int sp = c.indexOf(' ');
        if (sp >= 0) {
            c = c.substring(0, sp);
        }
        int colon = c.indexOf(':');
        if (colon >= 0) {
            c = c.substring(colon + 1);
        }
        c = c.toLowerCase();
        List<String> list = plugin.getConfig().getStringList("legit-commands");
        for (String s : list) {
            if (c.equals(s.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();
        String shown = msg.startsWith("/") ? msg.substring(1) : msg;
        if (shown.length() > 40) {
            shown = shown.substring(0, 40);
        }
        lastCommand.put(p.getUniqueId(), shown);
        lastCommandTick.put(p.getUniqueId(), (long) Bukkit.getCurrentTick());
        if (isLegitCommand(msg)) {
            globalLegitUntil = Bukkit.getCurrentTick() + 5L;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent e) {
        if (isLegitCommand(e.getCommand())) {
            globalLegitUntil = Bukkit.getCurrentTick() + 5L;
        }
    }

    // ------------------------------------------------------------------
    // 사망 보관분 저장 / 불러오기
    // ------------------------------------------------------------------
    public void loadPending() {
        pending.clear();
        if (!pendingFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        for (String key : y.getKeys(false)) {
            UUID u;
            try {
                u = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Map<EquipmentSlot, ItemStack> m = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot s : ARMOR) {
                ItemStack it = y.getItemStack(key + "." + s.name());
                if (!isEmpty(it)) {
                    m.put(s, it);
                }
            }
            if (!m.isEmpty()) {
                pending.put(u, m);
            }
        }
    }

    public void savePending() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<EquipmentSlot, ItemStack>> en : new ArrayList<>(pending.entrySet())) {
            for (Map.Entry<EquipmentSlot, ItemStack> s : en.getValue().entrySet()) {
                y.set(en.getKey() + "." + s.getKey().name(), s.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(pendingFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("pending.yml 저장 실패: " + ex.getMessage());
        }
    }
}
