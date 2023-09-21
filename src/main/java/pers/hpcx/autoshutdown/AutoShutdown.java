package pers.hpcx.autoshutdown;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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
import static pers.hpcx.autoshutdown.AutoShutdownConfig.ENABLE;
import static pers.hpcx.autoshutdown.AutoShutdownConfig.TIME;
import static pers.hpcx.autoshutdown.AutoShutdownUtils.*;

public class AutoShutdown
        implements ModInitializer, ServerLifecycleEvents.ServerStarting, ServerTickEvents.EndTick, CommandRegistrationCallback {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long MILLI_SECONDS_PER_SECOND = 1_000L;
    public static final long MILLI_SECONDS_PER_MINUTE = 60_000L;
    public static final long MILLI_SECONDS_PER_DAY = 86_400_000L;
    
    public boolean enable = false;
    public long time = 0L;
    public long shutdownTime;
    public long referenceTime;
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
        referenceTime = calendar.getTimeInMillis();
        updateTimer();
    }
    
    public void getProperties(Properties properties) {
        String enable = properties.getProperty(ENABLE.getKey());
        String timer = properties.getProperty(TIME.getKey());
        if (enable != null && !enable.isEmpty()) {
            this.enable = Boolean.parseBoolean(enable);
        }
        if (timer != null && !timer.isEmpty()) {
            try {
                this.time = parseTimer(timer);
            } catch (IllegalArgumentException e) {
                LOGGER.error("Failed to load time", e);
            }
        }
    }
    
    public void setProperties(Properties properties) {
        properties.setProperty(ENABLE.getKey(), Boolean.toString(enable));
        properties.setProperty(TIME.getKey(), formatTimer(time));
    }
    
    @Override
    public void onEndTick(MinecraftServer server) {
        if (!enable) {
            return;
        }
        long deltaTime = shutdownTime - System.currentTimeMillis();
        if (deltaTime <= 0) {
            server.stop(false);
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
        Predicate<ServerCommandSource> isOperator = source -> source.hasPermissionLevel(4);
        
        dispatcher.register(literal("sdtimer").requires(isOperator).then(literal("enable").then(
                argument(ENABLE.getKey(), ENABLE.getType()).executes(this::setEnable))));
        
        dispatcher.register(literal("sdtimer").requires(isOperator)
                                              .then(literal("set").then(argument(TIME.getKey(), TIME.getType()).executes(this::setTimer))));
    }
    
    public int setEnable(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        enable = BoolArgumentType.getBool(context, ENABLE.getKey());
        return storeProperty(player, ENABLE.getKey(), Boolean.toString(enable));
    }
    
    public int setTimer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        try {
            time = parseTimer(StringArgumentType.getString(context, TIME.getKey()));
            updateTimer();
        } catch (IllegalArgumentException e) {
            send(player, false, red(e.getMessage()));
            return 0;
        }
        return storeProperty(player, TIME.getKey(), formatTimer(time));
    }
    
    public void updateTimer() {
        long currentTime = System.currentTimeMillis();
        while (referenceTime + time <= currentTime) {
            referenceTime += MILLI_SECONDS_PER_DAY;
        }
        while (referenceTime + time > currentTime + MILLI_SECONDS_PER_DAY) {
            referenceTime -= MILLI_SECONDS_PER_DAY;
        }
        shutdownTime = referenceTime + time;
        long deltaTime = shutdownTime - currentTime;
        tenSecondsNotified = deltaTime <= MILLI_SECONDS_PER_SECOND * 10L;
        oneMinuteNotified = deltaTime <= MILLI_SECONDS_PER_MINUTE;
        fiveMinutesNotified = deltaTime <= MILLI_SECONDS_PER_MINUTE * 5L;
        tenMinutesNotified = deltaTime <= MILLI_SECONDS_PER_MINUTE * 10L;
    }
}
