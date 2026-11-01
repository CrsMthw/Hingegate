package com.crsmthw.hingegate.domain

import kotlinx.serialization.Serializable

/** Why a crack is showing: an unfold's roll, or the "Show crack now" button. */
@Serializable
enum class CrackCause { UNFOLD, MANUAL }

/** The crack currently showing, persisted so it survives a process restart while the phone stays unfolded. */
@Serializable
data class ActiveCrack(val spec: CrackSpec, val appearedAtEpochMs: Long, val cause: CrackCause)
