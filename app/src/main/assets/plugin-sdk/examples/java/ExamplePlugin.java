package example;

import com.aharou.plugin.api.AharouPlugin;
import com.aharou.plugin.api.PluginHost;

/** Trusted in-process plugin; the SDK has no Android or Kotlin dependency. */
public final class ExamplePlugin implements AharouPlugin {
    private PluginHost host;

    @Override
    public void onLoad(PluginHost host) throws Exception {
        this.host = host;
        host.call("log", "{\"message\":\"Java example loaded\"}");
    }

    @Override
    public String toolsJson() {
        return "[{\"name\":\"hello\",\"description\":\"Return a greeting\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}]";
    }

    @Override
    public String executeTool(String name, String argumentsJson, String contextJson) {
        if (!"hello".equals(name)) {
            throw new IllegalArgumentException("Unknown example tool: " + name);
        }
        return "{\"message\":\"Hello from a Java host plugin\",\"apiVersion\":"
                + host.apiVersion() + "}";
    }

    @Override
    public String onModelRequest(String requestJson) {
        return requestJson;
    }

    @Override
    public String onModelEvent(String eventJson) {
        return eventJson;
    }

    @Override
    public void onUnload() {
        host = null;
    }
}
