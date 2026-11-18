package com.aharou.plugin.api;

/** Android objects use Object so this SDK compiles without android.jar or Kotlin. */
public interface PluginHost {
    int apiVersion();
    String pluginDirectory();
    String dataDirectory();
    Object applicationContext();
    /** Release instance-owned bridge handles when this generation retires. */
    default void close() {}
    String call(String operation, String argumentsJson) throws Exception;
}
