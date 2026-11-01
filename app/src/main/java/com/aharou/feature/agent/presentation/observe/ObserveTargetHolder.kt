package com.aharou.feature.agent.presentation.observe

/**
 * 只读观察面板的「看谁」：顶栏/卡片上的「观察」入口写入，`observe` 路由读出来交给
 * [ObservePanelViewModel.bind]。
 *
 * 与 `toolDetail` 用全局 holder 传参是同一套做法——会话 id 不必拼进导航字符串，也就不必
 * 为每个入口各造一条路由。
 */
object ObserveTargetHolder {
    @Volatile
    var sessionId: String? = null
}
