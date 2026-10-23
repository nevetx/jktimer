package com.nevetx.roomtimer

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

/**
 * 平板身分資訊，供 PC 端（Electron）在區網中辨識與記住特定平板。
 * - [id]：首次啟動時產生並永久保存的 UUID；平板換 IP 後 PC 端仍能認出同一台
 * - [name]：Android「設定 → 關於平板 → 裝置名稱」，未設定時退回廠牌型號
 */
object DeviceIdentity {
    /** 區網搜尋時用來確認對方確實是 JK Timer 的識別字串 */
    const val APP_ID = "jktimer-gen2"

    private lateinit var appContext: Context
    lateinit var id: String
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("device", Context.MODE_PRIVATE)
        id = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }

    /** 每次讀取最新值，使用者在系統設定改名後立即生效 */
    val name: String
        get() {
            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
            return deviceName?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        }

    /** /api/info 與 UDP 廣播共用的身分資訊 */
    fun infoJson(): JSONObject = JSONObject().apply {
        put("app", APP_ID)
        put("name", RoomTimerApp.APP_NAME)
        put("version", BuildConfig.VERSION_NAME)
        put("deviceId", id)
        put("deviceName", name)
        put("port", RoomTimerApp.HTTP_PORT)
    }
}
