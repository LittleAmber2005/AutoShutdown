package pers.hpcx.autoshutdown;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static net.minecraft.util.Formatting.*;

public final class AutoShutdownUtils {
    
    public static final String CONFIG_COMMENTS = "auto-shutdown mod config";
    public static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("auto-shutdown.properties");
    
    private AutoShutdownUtils() {
    }
    
    public static String formatTime(long time) {
        long second = time / 1000L;
        long minute = second / 60L;
        long hour = minute / 60L;
        return "%02d:%02d:%02d".formatted(hour % 24L, minute % 60L, second % 60L);
    }
    
    public static String formatRelativeTime(long time) {
        long second = time / 1000L;
        long minute = second / 60L;
        long hour = minute / 60L;
        return "%dh %dm %ds".formatted(hour % 24L, minute % 60L, second % 60L);
    }
    
    public static long parseTime(String time) {
        String[] split = null;
        if (time.contains(":")) {
            split = time.split(":");
        } else if (time.contains("-")) {
            split = time.split("-");
        }
        if (split == null || split.length != 3) {
            throw new IllegalArgumentException("incorrect time format");
        }
        long hour = Math.min(Math.max(Long.parseLong(split[0]), 0), 23);
        long minute = Math.min(Math.max(Long.parseLong(split[1]), 0), 59);
        long second = Math.min(Math.max(Long.parseLong(split[2]), 0), 59);
        return ((hour * 60L + minute) * 60L + second) * 1000L;
    }
    
    public static MutableText green(String str) {
        return Text.literal(str).formatted(GREEN);
    }
    
    public static MutableText red(String str) {
        return Text.literal(str).formatted(RED);
    }
    
    public static MutableText yellow(String str) {
        return Text.literal(str).formatted(YELLOW);
    }
    
    public static MutableText purple(String str) {
        return Text.literal(str).formatted(LIGHT_PURPLE);
    }
    
    public static MutableText gray(String str) {
        return Text.literal(str).formatted(GRAY);
    }
    
    public static void send(ServerCommandSource source, boolean success, MutableText... texts) {
        MutableText comp = MutableText.of(TextContent.EMPTY);
        for (MutableText text : texts) {
            comp.append(text);
        }
        if (success) {
            source.sendMessage(comp);
        } else {
            source.sendError(comp);
        }
    }
    
    public static int storeProperty(ServerCommandSource source, String key, String value) {
        send(source, true, purple(key), green(" set to "), yellow(value));
        Properties properties = new Properties();
        
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            properties.load(in);
        } catch (IOException e) {
            send(source, false, gray("Failed to read config file: "), red(e.getMessage()));
            return 0;
        }
        
        properties.setProperty(key, value);
        
        try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
            properties.store(out, CONFIG_COMMENTS);
        } catch (IOException e) {
            send(source, false, gray("Failed to write config file: "), red(e.getMessage()));
            return 0;
        }
        
        return 1;
    }
}
