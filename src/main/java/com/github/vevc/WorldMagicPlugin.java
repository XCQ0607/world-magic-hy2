package com.github.vevc;

import com.github.vevc.config.AppConfig;
import com.github.vevc.service.impl.Hy2ServiceImpl;
import com.github.vevc.util.ConfigUtil;
import com.github.vevc.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Properties;

/**
 * @author vevc
 */
public final class WorldMagicPlugin extends JavaPlugin {

    private final Hy2ServiceImpl appService = new Hy2ServiceImpl();

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getLogger().info("WorldMagicPlugin enabled");
        LogUtil.init(this);
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            // load config
            Properties props = ConfigUtil.loadConfiguration();
            AppConfig appConfig = AppConfig.load(props);
            if (Objects.isNull(appConfig)) {
                Bukkit.getGlobalRegionScheduler().run(this, task2 -> {
                    this.getLogger().info("Configuration not found, disabling plugin");
                    Bukkit.getPluginManager().disablePlugin(this);
                });
                return;
            }

            // install & start apps
            if (this.installApps(appConfig)) {
                Bukkit.getGlobalRegionScheduler().run(this, task2 -> {
                    Bukkit.getAsyncScheduler().runNow(this, t -> appService.startup());
                    Bukkit.getAsyncScheduler().runNow(this, t -> appService.clean());
                });
            } else {
                Bukkit.getGlobalRegionScheduler().run(this, task2 -> {
                    this.getLogger().info("Plugin install failed, disabling plugin");
                    Bukkit.getPluginManager().disablePlugin(this);
                });
            }
        });
    }

    private boolean installApps(AppConfig appConfig) {
        try {
            appService.install(appConfig);
            return true;
        } catch (Exception e) {
            LogUtil.error("Plugin install failed", e);
            return false;
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        this.getLogger().info("WorldMagicPlugin disabled");
    }
}
