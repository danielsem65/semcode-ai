package com.danielsem65.semcodeai.core

/** Tracks whether the app UI is in the foreground (used to decide on notifications). */
object AppForeground {
    @Volatile var foreground: Boolean = false
}
