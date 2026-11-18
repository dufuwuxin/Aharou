// Trusted in-process plugin, not a sandbox. host.call takes and returns JSON text.
var plugin = {
    onLoad: function (host) {
        host.call("log", JSON.stringify({message: "JavaScript example loaded"}));
    },
    toolsJson: function () {
        return JSON.stringify([{
            name: "hello",
            description: "Return a greeting",
            parameters: {type: "object", properties: {}, required: []}
        }]);
    },
    executeTool: function (name, argumentsJson, contextJson) {
        if (name !== "hello") throw new Error("Unknown example tool: " + name);
        return JSON.stringify({message: "Hello from a JavaScript host plugin"});
    },
    // Same host objects as DEX: host.reflect('context') returns an instance-owned handle.
    onContext: function (contextJson) {
        var input = JSON.parse(contextJson);
        input.systemPrompt += "\n[Example trusted plugin context]";
        return JSON.stringify(input);
    },
    onModelRequest: function (requestJson) { return requestJson; },
    onModelEvent: function (eventJson) { return eventJson; },
    onUnload: function () {}
};
