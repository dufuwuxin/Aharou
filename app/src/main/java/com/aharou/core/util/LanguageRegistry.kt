package com.aharou.core.util

/** 固定中文界面的语言标识。 */
data class SupportedLanguage(
    val tag: String,
    val displayName: String
)

object LanguageRegistry {

    /** 跟随系统的占位值，非真实语言 tag。 */
    const val FOLLOW_SYSTEM = ""

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage("zh", "中文")
    )

    /** 判断 tag 是否为已支持的真实语言（排除 [FOLLOW_SYSTEM]）。 */
    fun isSupported(tag: String): Boolean = languages.any { it.tag == tag }
}
