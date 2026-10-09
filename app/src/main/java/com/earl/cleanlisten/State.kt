package com.earl.cleanlisten

object State {
    @Volatile var running = false
    @Volatile var volumeServiceOn = false
    @Volatile var status = "Off"
    @Volatile var lastWords = ""
    @Volatile var muted = 0
    @Volatile var level = 0f        // 0..1 loudness of captured audio
    @Volatile var delaySec = 5
    @Volatile var tier = 2
}
