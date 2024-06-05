package com.example.terminalble

class Constants private constructor() {

    companion object {
        // valores deben ser globalmente únicos
        const val INTENT_ACTION_DISCONNECT: String = BuildConfig.APPLICATION_ID + ".Disconnect"
    }
}