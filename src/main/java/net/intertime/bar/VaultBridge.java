package net.intertime.bar;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/** З'єднання з економікою сервера через Vault (Essentials та інші). Без залежності при збірці. */
final class VaultBridge {

    private final Object eco;
    private final Method mBal;
    private final Method mWithdraw;
    private final Method mDeposit;

    private VaultBridge(Object eco, Method mBal, Method mWithdraw, Method mDeposit) {
        this.eco = eco;
        this.mBal = mBal;
        this.mWithdraw = mWithdraw;
        this.mDeposit = mDeposit;
    }

    /** Повертає null, якщо Vault або економіка не знайдені. */
    static VaultBridge create() {
        try {
            Class<?> c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return null;
            Object eco = rsp.getProvider();
            return new VaultBridge(eco,
                    c.getMethod("getBalance", OfflinePlayer.class),
                    c.getMethod("withdrawPlayer", OfflinePlayer.class, double.class),
                    c.getMethod("depositPlayer", OfflinePlayer.class, double.class));
        } catch (Throwable t) {
            return null;
        }
    }

    long balance(OfflinePlayer p) {
        try {
            return (long) Math.floor((Double) mBal.invoke(eco, p));
        } catch (Exception e) {
            return 0;
        }
    }

    boolean withdraw(OfflinePlayer p, long amount) {
        if (amount <= 0) return true;
        if (balance(p) < amount) return false;
        try {
            Object resp = mWithdraw.invoke(eco, p, (double) amount);
            return (Boolean) resp.getClass().getMethod("transactionSuccess").invoke(resp);
        } catch (Exception e) {
            return false;
        }
    }

    void deposit(OfflinePlayer p, long amount) {
        if (amount <= 0) return;
        try {
            mDeposit.invoke(eco, p, (double) amount);
        } catch (Exception ignored) {
        }
    }
}
