package pers.hpcx.autoshutdown;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.Properties;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.util.Formatting.BOLD;
import static pers.hpcx.autoshutdown.AutoShutdownConfig.*;
import static pers.hpcx.autoshutdown.AutoShutdownUtils.*;

public class AutoShutdown
        implements ModInitializer, ServerLifecycleEvents.ServerStarting, ServerTickEvents.EndTick, CommandRegistrationCallback {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long MILLI_SECONDS_PER_SECOND = 1_000L;
    public static final long MILLI_SECONDS_PER_MINUTE = 60_000L;
    public static final long MILLI_SECONDS_PER_DAY = 86_400_000L;
    
    public boolean enableTimer = false;
    public boolean enableDelayer = false;
    public long timer = 0L;
    public long delayer = 0L;
    public long shutdownTime;
    public long timerReferenceTime;
    public long delayerReferenceTime;
    public boolean oneSecondNotified;
    public boolean tenSecondsNotified;
    public boolean oneMinuteNotified;
    public boolean fiveMinutesNotified;
    public boolean tenMinutesNotified;
    
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(this);
        ServerTickEvents.END_SERVER_TICK.register(this);
        CommandRegistrationCallback.EVENT.register(this);
    }
    
    @Override
    public void onServerStarting(MinecraftServer server) {
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                    Properties properties = new Properties();
                    properties.load(in);
                    getProperties(properties);
                }
            } else {
                Files.createFile(CONFIG_PATH);
                try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                    Properties properties = new Properties();
                    setProperties(properties);
                    properties.store(out, CONFIG_COMMENTS);
                }
            }
        } catch (IOException ignored) {
        }
        
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        timerReferenceTime = calendar.getTimeInMillis();
        delayerReferenceTime = System.currentTimeMillis();
        alignTimer();
        updateTime();
    }
    
    public void getProperties(Properties properties) {
        String enableTimer = properties.getProperty(ENABLE_TIMER.getKey());
        if (enableTimer != null && !enableTimer.isEmpty()) {
            this.enableTimer = Boolean.parseBoolean(enableTimer);
        }
        
        String timer = properties.getProperty(TIMER.getKey());
        if (timer != null && !timer.isEmpty()) {
            try {
                this.timer = parseTime(timer);
            } catch (IllegalArgumentException e) {
                this.enableTimer = false;
                LOGGER.error("Failed to load timer", e);
            }
        }
    }
    
    public void setProperties(Properties properties) {
        properties.setProperty(ENABLE_TIMER.getKey(), Boolean.toString(enableTimer));
        properties.setProperty(TIMER.getKey(), formatTime(timer));
    }
    
    @Override
    public void onEndTick(MinecraftServer server) {
        if (!enableTimer && !enableDelayer) {
            return;
        }
        long deltaTime = shutdownTime - System.currentTimeMillis();
        if (deltaTime <= 0) {
            server.stop(false);
        } else if (deltaTime <= MILLI_SECONDS_PER_SECOND) {
            if (!oneSecondNotified) {
                oneSecondNotified = true;
                server.getPlayerManager().broadcast(red("Server will shutdown immediately").formatted(BOLD), true);
            }
        } else if (deltaTime <= MILLI_SECONDS_PER_SECOND * 10L) {
            if (!tenSecondsNotified) {
                tenSecondsNotified = true;
                server.getPlayerManager().broadcast(red("Server will shutdown within 10 seconds").formatted(BOLD), true);
            }
        } else if (deltaTime <= MILLI_SECONDS_PER_MINUTE) {
            if (!oneMinuteNotified) {
                oneMinuteNotified = true;
                server.getPlayerManager().broadcast(red("Server will shutdown within 1 minute"), true);
            }
        } else if (deltaTime <= MILLI_SECONDS_PER_MINUTE * 5L) {
            if (!fiveMinutesNotified) {
                fiveMinutesNotified = true;
                server.getPlayerManager().broadcast(yellow("Server will shutdown within 5 minutes"), true);
            }
        } else if (deltaTime <= MILLI_SECONDS_PER_MINUTE * 10L) {
            if (!tenMinutesNotified) {
                tenMinutesNotified = true;
                server.getPlayerManager().broadcast(green("Server will shutdown within 10 minutes"), true);
            }
        }
    }
    
    @Override
    public void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess,
                         CommandManager.RegistrationEnvironment environment) {
        Predicate<ServerCommandSource> isOperator = source -> source.hasPermissionLevel(4) || "Server".equals(source.getName());
        
        dispatcher.register(literal("sd").requires(isOperator).then(literal("timer").then(
                literal("enable").then(argument(ENABLE_TIMER.getKey(), ENABLE_TIMER.getType()).executes(this::setEnableTimer)))));
        
        dispatcher.register(literal("sd").requires(isOperator).then(literal("delayer").then(
                literal("enable").then(argument(ENABLE_DELAYER.getKey(), ENABLE_DELAYER.getType()).executes(this::setEnableDelayer)))));
        
        dispatcher.register(literal("sd").requires(isOperator).then(literal("timer").then(
                literal("set").then(argument(TIMER.getKey(), TIMER.getType()).executes(this::setTimer)))));
        
        dispatcher.register(literal("sd").requires(isOperator).then(literal("delayer").then(
                literal("set").then(argument(DELAYER.getKey(), DELAYER.getType()).executes(this::setDelayer)))));
    }
    
    public int setEnableTimer(CommandContext<ServerCommandSource> context) {
        boolean old = enableTimer;
        enableTimer = BoolArgumentType.getBool(context, ENABLE_TIMER.getKey());
        if (old != enableTimer) {
            if (enableTimer) {
                alignTimer();
            }
            updateTime();
        }
        return storeProperty(context.getSource(), ENABLE_TIMER.getKey(), Boolean.toString(enableTimer));
    }
    
    public int setEnableDelayer(CommandContext<ServerCommandSource> context) {
        boolean old = enableDelayer;
        enableDelayer = BoolArgumentType.getBool(context, ENABLE_DELAYER.getKey());
        if (old != enableDelayer) {
            if (enableDelayer) {
                delayer = Math.max(delayer, MILLI_SECONDS_PER_MINUTE);
                delayerReferenceTime = System.currentTimeMillis();
            }
            updateTime();
        }
        if (enableDelayer) {
            send(context.getSource(), true, green("Server will shutdown in "), yellow(formatRelativeTime(delayer)));
        } else {
            send(context.getSource(), true, green("Shutdown delayer disabled"));
        }
        return 1;
    }
    
    public int setTimer(CommandContext<ServerCommandSource> context) {
        try {
            timer = parseTime(StringArgumentType.getString(context, TIMER.getKey()));
            alignTimer();
        } catch (IllegalArgumentException e) {
            send(context.getSource(), false, red(e.getMessage()));
            return 0;
        }
        updateTime();
        return storeProperty(context.getSource(), TIMER.getKey(), formatTime(timer));
    }
    
    public int setDelayer(CommandContext<ServerCommandSource> context) {
        try {
            delayer = parseTime(StringArgumentType.getString(context, DELAYER.getKey()));
            delayerReferenceTime = System.currentTimeMillis();
        } catch (IllegalArgumentException e) {
            send(context.getSource(), false, red(e.getMessage()));
            return 0;
        }
        updateTime();
        if (enableDelayer) {
            send(context.getSource(), true, green("Server will shutdown in "), yellow(formatRelativeTime(delayer)));
        } else {
            send(context.getSource(), true, green("Shutdown delayer set to "), yellow(formatRelativeTime(delayer)));
        }
        return 1;
    }
    
    public void alignTimer() {
        long currentTime = System.currentTimeMillis();
        while (timerReferenceTime + timer <= currentTime) {
            timerReferenceTime += MILLI_SECONDS_PER_DAY;
        }
        while (timerReferenceTime + timer > currentTime + MILLI_SECONDS_PER_DAY) {
            timerReferenceTime -= MILLI_SECONDS_PER_DAY;
        }
    }
    
    public void updateTime() {
        if (enableTimer && enableDelayer) {
            shutdownTime = Math.min(timerReferenceTime + timer, delayerReferenceTime + delayer);
        } else if (enableTimer) {
            shutdownTime = timerReferenceTime + timer;
        } else if (enableDelayer) {
            shutdownTime = delayerReferenceTime + delayer;
        } else {
            shutdownTime = 0L;
        }
        oneSecondNotified = false;
        tenSecondsNotified = false;
        oneMinuteNotified = false;
        fiveMinutesNotified = false;
        tenMinutesNotified = false;
    }
}
