package pers.hpcx.autoshutdown;

import com.mojang.brigadier.arguments.ArgumentType;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.StringArgumentType.string;

public enum AutoShutdownConfig {
    
    ENABLE(bool()),
    TIME(string());
    
    private final String key;
    private final ArgumentType<?> type;
    
    AutoShutdownConfig(ArgumentType<?> type) {
        this.type = type;
        this.key = toString().toLowerCase().replace('_', '-');
    }
    
    public String getKey() {
        return key;
    }
    
    public ArgumentType<?> getType() {
        return type;
    }
}
