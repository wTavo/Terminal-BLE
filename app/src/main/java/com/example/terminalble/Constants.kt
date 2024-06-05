package com.example.terminalble

class Constants private constructor() {

    companion object {
        // valores deben ser globalmente únicos
        const val INTENT_ACTION_DISCONNECT: String = BuildConfig.APPLICATION_ID + ".Disconnect"
        const val NOTIFICATION_CHANNEL: String = BuildConfig.APPLICATION_ID + ".Channel"
        const val INTENT_CLASS_MAIN_ACTIVITY: String = BuildConfig.APPLICATION_ID + ".MainActivity"

        // valores deben ser únicos dentro de cada app
        const val NOTIFY_MANAGER_START_FOREGROUND_SERVICE: Int = 1001
    }
}