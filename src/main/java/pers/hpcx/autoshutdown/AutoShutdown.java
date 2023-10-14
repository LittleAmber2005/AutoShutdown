package pers.hpcx.autoshutdown;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
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

public class AutoShutdown implements ModInitializer {
    
    public static final long MILLI_SECONDS_PER_SECOND = 1_000L;
    public static final long MILLI_SECONDS_PER_MINUTE = 60_000L;
    public static final long MILLI_SECONDS_PER_DAY = 86_400_000L;
    public static final double PITCH_STEP = 1.05946309435929526456;
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public boolean enableTimer;
    public boolean enableDelayer;
    public long timer;
    public long delayer;
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
        ServerLifecycleEvents.SERVER_STARTING.register(loadConfig);
        ServerLifecycleEvents.SERVER_STARTING.register(initShutdownTime);
        ServerTickEvents.END_SERVER_TICK.register(checkTime);
        CommandRegistrationCallback.EVENT.register(commandRegistrationCallback);
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
    
    public void alignTimer() {
        long currentTime = System.currentTimeMillis();
        while (timerReferenceTime + timer <= currentTime) {
            timerReferenceTime += MILLI_SECONDS_PER_DAY;
        }
        while (timerReferenceTime + timer > currentTime + MILLI_SECONDS_PER_DAY) {
            timerReferenceTime -= MILLI_SECONDS_PER_DAY;
        }
    }
    
    public void updateShutdownTime() {
        long time0 = enableTimer ? timerReferenceTime + timer : Long.MAX_VALUE;
        long time1 = enableDelayer ? delayerReferenceTime + delayer : Long.MAX_VALUE;
        shutdownTime = Math.min(time0, time1);
        oneSecondNotified = false;
        tenSecondsNotified = false;
        oneMinuteNotified = false;
        fiveMinutesNotified = false;
        tenMinutesNotified = false;
    }
    
    public void broadcast(MinecraftServer server, Text message, float pitch) {
        LOGGER.info(message.getString());
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessageToClient(message, true);
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 1.0f, pitch);
        }
    }
    
    public final ServerLifecycleEvents.ServerStarting loadConfig = server -> {
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
        } catch (IOException e) {
            LOGGER.error("failed to load config file", e);
        }
    };
    
    public final ServerLifecycleEvents.ServerStarting initShutdownTime = server -> {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        timerReferenceTime = calendar.getTimeInMillis();
        delayerReferenceTime = System.currentTimeMillis();
        alignTimer();
        updateShutdownTime();
    };
    
    public final ServerTickEvents.EndTick checkTime = server -> {
        if (!enableTimer && !enableDelayer) {
            return;
        }
        long deltaTime = shutdownTime - System.currentTimeMillis();
        if (deltaTime <= 0) {
            server.stop(false);
            return;
        }
        if (deltaTime <= MILLI_SECONDS_PER_SECOND) {
            if (!oneSecondNotified) {
                oneSecondNotified = true;
                broadcast(server, red("Server will shutdown immediately").formatted(BOLD), (float) Math.pow(PITCH_STEP, 7));
            }
            return;
        }
        if (deltaTime <= MILLI_SECONDS_PER_SECOND * 10L) {
            if (!tenSecondsNotified) {
                tenSecondsNotified = true;
                broadcast(server, red("Server will shutdown within 10 seconds").formatted(BOLD), (float) Math.pow(PITCH_STEP, 5));
            }
            return;
        }
        if (deltaTime <= MILLI_SECONDS_PER_MINUTE) {
            if (!oneMinuteNotified) {
                oneMinuteNotified = true;
                broadcast(server, red("Server will shutdown within 1 minute"), (float) Math.pow(PITCH_STEP, 4));
            }
            return;
        }
        if (deltaTime <= MILLI_SECONDS_PER_MINUTE * 5L) {
            if (!fiveMinutesNotified) {
                fiveMinutesNotified = true;
                broadcast(server, yellow("Server will shutdown within 5 minutes"), (float) Math.pow(PITCH_STEP, 2));
            }
            return;
        }
        if (deltaTime <= MILLI_SECONDS_PER_MINUTE * 10L) {
            if (!tenMinutesNotified) {
                tenMinutesNotified = true;
                broadcast(server, green("Server will shutdown within 10 minutes"), (float) Math.pow(PITCH_STEP, 0));
            }
        }
    };
    
    public final CommandRegistrationCallback commandRegistrationCallback = (dispatcher, registryAccess, environment) -> {
        Predicate<ServerCommandSource> isOperator = source -> source.hasPermissionLevel(4) || "Server".equals(source.getName());
        
        dispatcher.register(literal("shutdown").then(literal("info").executes(this::info)));
        
        dispatcher.register(literal("shutdown").requires(isOperator).then(literal("timer").then(literal("enable").then(argument(ENABLE_TIMER.getKey(), ENABLE_TIMER.getType()).executes(this::setEnableTimer)))));
        
        dispatcher.register(literal("shutdown").requires(isOperator).then(literal("timer").then(literal("set").then(argument(TIMER.getKey(), TIMER.getType()).executes(this::setTimer)))));
        
        dispatcher.register(literal("shutdown").requires(isOperator).then(literal("delayer").then(literal("enable").then(argument(ENABLE_DELAYER.getKey(), ENABLE_DELAYER.getType()).executes(this::setEnableDelayer)))));
        
        dispatcher.register(literal("shutdown").requires(isOperator).then(literal("delayer").then(literal("set").then(argument(DELAYER.getKey(), DELAYER.getType()).executes(this::setDelayer)))));
    };
    
    public int info(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (enableTimer) {
            send(source, true, green("Shutdown timer set to "), yellow(formatTime(timer)));
        } else {
            send(source, true, green("Shutdown timer "), yellow("disabled"));
        }
        if (enableDelayer) {
            send(source, true, green("Shutdown delayer set to "), yellow(formatTime(delayer)));
        } else {
            send(source, true, green("Shutdown delayer "), yellow("disabled"));
        }
        if (enableTimer || enableDelayer) {
            long deltaTime = shutdownTime - System.currentTimeMillis();
            send(source, true, green("Server will shutdown in "), yellow(formatRelativeTime(deltaTime)));
        }
        return 1;
    }
    
    public int setEnableTimer(CommandContext<ServerCommandSource> context) {
        enableTimer = BoolArgumentType.getBool(context, ENABLE_TIMER.getKey());
        alignTimer();
        updateShutdownTime();
        return storeProperty(context.getSource(), ENABLE_TIMER.getKey(), Boolean.toString(enableTimer));
    }
    
    public int setTimer(CommandContext<ServerCommandSource> context) {
        try {
            timer = parseTime(StringArgumentType.getString(context, TIMER.getKey()));
        } catch (IllegalArgumentException e) {
            send(context.getSource(), false, red(e.getMessage()));
            return 0;
        }
        alignTimer();
        updateShutdownTime();
        return storeProperty(context.getSource(), TIMER.getKey(), formatTime(timer));
    }
    
    public int setEnableDelayer(CommandContext<ServerCommandSource> context) {
        boolean old = enableDelayer;
        enableDelayer = BoolArgumentType.getBool(context, ENABLE_DELAYER.getKey());
        if (!old && enableDelayer) {
            delayerReferenceTime = System.currentTimeMillis();
            delayer = Math.max(delayer, MILLI_SECONDS_PER_MINUTE);
        }
        updateShutdownTime();
        if (enableDelayer) {
            send(context.getSource(), true, green("Server will shutdown in "), yellow(formatRelativeTime(delayer)));
        } else {
            send(context.getSource(), true, green("Shutdown delayer disabled"));
        }
        return 1;
    }
    
    public int setDelayer(CommandContext<ServerCommandSource> context) {
        try {
            delayerReferenceTime = System.currentTimeMillis();
            delayer = parseTime(StringArgumentType.getString(context, DELAYER.getKey()));
        } catch (IllegalArgumentException e) {
            send(context.getSource(), false, red(e.getMessage()));
            return 0;
        }
        updateShutdownTime();
        if (enableDelayer) {
            send(context.getSource(), true, green("Server will shutdown in "), yellow(formatRelativeTime(delayer)));
        } else {
            send(context.getSource(), true, green("Shutdown delayer set to "), yellow(formatRelativeTime(delayer)));
        }
        return 1;
    }
}
