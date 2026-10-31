package com.crsmthw.hingegate.domain

/** Whether the inner (large) screen is the one in use. UNKNOWN until a signal can tell the two apart. */
enum class Posture { UNKNOWN, FOLDED, UNFOLDED }

/** Which signal decided the current [Posture] (docs/DESIGN.md §4). Shown in the UI's Detection section. */
enum class PostureAuthority { NONE, DISPLAY_SIZE, WINDOW_MANAGER }

/** A change of posture between two KNOWN postures; the only thing the crack trigger reacts to. */
enum class Transition { UNFOLDED, FOLDED }
