package com.ch.armorhide;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CHArmorHide extends JavaPlugin {

    private ArmorGuard guard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guard = new ArmorGuard(this);
        guard.loadPending();
        getServer().getPluginManager().registerEvents(guard, this);
        getLogger().info("CH-ArmorHide v" + getDescription().getVersion() + " 활성화됨.");
    }

    @Override
    public void onDisable() {
        if (guard != null) {
            guard.savePending();
        }
        getLogger().info("CH-ArmorHide 비활성화됨.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("armorhide")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("§e사용법: /armorhide <reload|allow <닉네임> [초]>");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            reloadConfig();
            sender.sendMessage("§aCH-ArmorHide 설정이 리로드되었습니다.");
            return true;
        }
        if (sub.equals("allow")) {
            if (args.length < 2) {
                sender.sendMessage("§e사용법: /armorhide allow <닉네임> [초]  (그 시간 동안 방어구가 없어져도 복구하지 않음, 기본 30초)");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§c접속 중인 플레이어가 아닙니다.");
                return true;
            }
            int sec = 30;
            if (args.length >= 3) {
                try {
                    sec = Math.max(1, Math.min(600, Integer.parseInt(args[2])));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c초는 숫자로 입력하세요.");
                    return true;
                }
            }
            guard.markLegit(target, sec * 20);
            sender.sendMessage("§a" + target.getName() + " 님의 방어구 복구를 " + sec + "초 동안 멈춥니다.");
            return true;
        }
        sender.sendMessage("§e사용법: /armorhide <reload|allow <닉네임> [초]>");
        return true;
    }
}
