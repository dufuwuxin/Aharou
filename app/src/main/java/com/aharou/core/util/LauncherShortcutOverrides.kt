package com.aharou.core.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aharou.MainActivity
import com.aharou.R

/**
 * 用同 id 的动态快捷方式覆盖 res/xml/shortcuts.xml 的静态快捷方式。
 *
 * 静态那份为了兼容 flavor 只能写隐式 action（res/xml 不支持 ${applicationId} 占位符），
 * 同机装原版与 fork 时会互相抢；这里用 context.packageName 决定的显式组件推一份同 id 的
 * 动态快捷方式，applicationId / debug / beta 自动正确。
 *
 * 用 ContentProvider 承载是因为它比 Application.onCreate 更早执行。
 */
class LauncherShortcutOverrides : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(ctx, listOf("start_voice"))
            ShortcutManagerCompat.disableShortcuts(ctx, listOf("start_voice"), null)
            push(ctx, "new_chat", R.string.shortcut_new_chat, R.drawable.ic_pet_say, ACTION_NEW_CHAT)
        }.onFailure { e ->
            // 快捷方式推送失败绝不能影响启动。
            FileLogger.e(TAG, "覆盖静态快捷方式失败", e)
        }
        return true
    }

    private fun push(ctx: Context, id: String, labelRes: Int, iconRes: Int, action: String) {
        val info = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(ctx.getString(labelRes))
            .setIcon(IconCompat.createWithResource(ctx, iconRes))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(action))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(ctx, info)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "LauncherShortcutOverrides"
        const val ACTION_NEW_CHAT = "com.aharou.intent.NEW_CHAT"
    }
}
