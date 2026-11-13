package com.aharou.plugin.api;

/** SDK v1. Plugins are trusted code running with the host UID, not a sandbox. */
public interface AharouPlugin {
    default void onLoad(PluginHost host) throws Exception {}
    default void onUnload() {}
    default String toolsJson() { return "[]"; }
    default String executeTool(String name, String argumentsJson, String contextJson) { return "null"; }
    default String onModelRequest(String requestJson) { return requestJson; }
    default String onModelEvent(String eventJson) { return eventJson; }
    default Object createView(Object androidContext) { return null; }
}
