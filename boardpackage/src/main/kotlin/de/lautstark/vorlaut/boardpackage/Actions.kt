package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonObject

/**
 * Works out what pressing a button does (SPEC.md 7.3 and 7.4).
 */
internal object Actions {
    /** The whole of what SPEC.md 7.4 requires an importer to implement. */
    private val IMPLEMENTED: Map<String, OnActivate> =
        mapOf(
            ":clear" to OnActivate.Clear,
            ":backspace" to OnActivate.Backspace,
            ":speak" to OnActivate.SpeakBar,
            ":home" to OnActivate.Home,
        )

    fun classify(
        button: JsonObject,
        boardId: String,
        buttonId: String,
        warnings: WarningList,
    ): OnActivate {
        // SPEC.md 7.3: load_board takes precedence over an action if a button
        // somehow carries both.
        button.obj("load_board")?.str("id")?.let { return OnActivate.Navigate(it) }

        val actions = button.arr("actions")?.mapNotNull { it.asStringOrNull() }
        if (!actions.isNullOrEmpty()) {
            val unimplemented = actions.firstOrNull { it !in IMPLEMENTED }
            if (unimplemented != null) {
                // SPEC.md 7.4: one unimplemented action disables the *whole*
                // button. The importer must not run the prefix it understands -
                // the sequence was authored as one thing, and half-running it is a
                // wrong outcome rather than a partial one. Fixture unknown-action,
                // button u3, is exactly this: `:clear` must not run on its own.
                return disable(unimplemented, boardId, buttonId, warnings)
            }
            val resolved = actions.map { IMPLEMENTED.getValue(it) }
            return resolved.singleOrNull() ?: OnActivate.Sequence(resolved)
        }

        button.str("action")?.let { action ->
            return IMPLEMENTED[action] ?: disable(action, boardId, buttonId, warnings)
        }

        // SPEC.md 4.3. OBF cannot express "speak this now and leave the bar
        // alone", and interjections need it: Ouch!, stop that, a greeting.
        // Composing those into a sentence first defeats their purpose.
        if (button.bool("ext_lautstark_speak_immediately") == true) return OnActivate.SpeakImmediately

        return OnActivate.Append
    }

    private fun disable(
        action: String,
        boardId: String,
        buttonId: String,
        warnings: WarningList,
    ): OnActivate {
        // The button renders and is visibly dead. SPEC.md 7.4 is emphatic that it
        // must not silently do nothing: a button that looks live and ignores the
        // person pressing it teaches them the device ignores them, which is the
        // one failure mode a communication aid cannot afford.
        warnings.add(WarningCode.ACTION_UNSUPPORTED, boardId, buttonId, action)
        return OnActivate.Disabled
    }
}
