package com.overdrive.app.ui.util

/**
 * Holds the recording playlist for the current player session.
 * Set by RecordingLibraryFragment before navigating to the player so
 * MultiCameraPlayerFragment can navigate prev/next within the same filtered list.
 */
object PlayerPlaylist {
    @Volatile var paths: List<String> = emptyList()
    @Volatile var titles: List<String> = emptyList()
    @Volatile var currentIndex: Int = 0

    val currentPath: String get() = paths.getOrElse(currentIndex) { "" }
    val currentTitle: String get() = titles.getOrElse(currentIndex) { "" }

    fun hasPrev(): Boolean = currentIndex > 0
    fun hasNext(): Boolean = currentIndex < paths.size - 1
}
