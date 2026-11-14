package com.kieronquinn.app.ambientmusicmod.diagnostics

/** Process-wide holder for errors that were previously swallowed. */
object DiagnosticsState {
    @Volatile var lastPointerError: String? = null
}
