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
        // SPEC.md 7.3, since 1.2.0: the flag is a modifier and not a row of the
        // table. It does not decide which navigation happens - it says the
        // button puts its entry in the bar on the way through - so it is read
        // once here and applied to whatever navigation the button turns out to
        // carry. On anything else it is ignored, in silence: no warning and no
        // fault, because an appending button already appends and a
        // speak-immediately button carrying it meant something the format has
        // no way to say. Fixture navigate-and-append pins that on its c3.
        val carries = button.bool("ext_lautstark_append_on_navigate") == true

        // SPEC.md 7.3, since 1.4.0: the appending modifier's sibling, read the
        // same way and ignored the same way. What differs is its reach, and the
        // difference is deliberate rather than an omission - see [speaking].
        val speaks = button.bool("ext_lautstark_speak_on_navigate") == true

        // SPEC.md 7.3: load_board takes precedence over an action if a button
        // somehow carries both.
        button.obj("load_board")?.str("id")?.let {
            return speaking(OnActivate.Navigate(it), speaks, carries)
                ?: carrying(OnActivate.Navigate(it), carries)
        }

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
            // A one-element array is the same button as the singular field, so
            // it carries the same way. SPEC.md 7.3 names `action: ":home"` and
            // says nothing about `actions: [":home"]`; the two are one button
            // written twice, and a reading where only one of them may carry a
            // word is a distinction nobody could explain to the person building
            // the board. A longer sequence carries nothing - the flag says
            // "append before navigating", and there is no navigation in a
            // sequence, only steps.
            return resolved.singleOrNull()?.let { carrying(it, carries) } ?: OnActivate.Sequence(resolved)
        }

        button.str("action")?.let { action ->
            // A disabled button appends nothing either: doing nothing at all is
            // what disabled means, and carrying() is not reached for it.
            return IMPLEMENTED[action]?.let { carrying(it, carries) }
                ?: disable(action, boardId, buttonId, warnings)
        }

        // SPEC.md 4.3. OBF cannot express "speak this now and leave the bar
        // alone", and interjections need it: Ouch!, stop that, a greeting.
        // Composing those into a sentence first defeats their purpose.
        if (button.bool("ext_lautstark_speak_immediately") == true) return OnActivate.SpeakImmediately

        return OnActivate.Append
    }

    /** SPEC.md 7.3's append-on-navigate, applied where there is a navigation to
     *  apply it to. Everything else is returned untouched. */
    private fun carrying(
        resolved: OnActivate,
        carries: Boolean,
    ): OnActivate = if (carries && resolved is OnActivate.Navigation) OnActivate.AppendThenNavigate(resolved) else resolved

    /**
     * SPEC.md 7.3's speak-on-navigate, which rides on `load_board` and nothing
     * else. Null when the flag is absent, so the caller falls through to
     * [carrying] and the appending modifier answers on its own.
     *
     * **This is only ever called at the `load_board` site**, which is the whole
     * of the narrowing: SPEC.md 7.3 says the modifier is not extended to
     * `action: ":home"` and MUST be ignored beside it, so the `:home` sites
     * below never ask. Nothing warns there - an ignored flag is ignored in
     * silence, exactly as the appending one is where it has no navigation.
     * Fixture `navigate-and-speak` pairs its `e2` and `e3` to pin it: a `:home`
     * button carrying the flag and one without must be indistinguishable.
     */
    private fun speaking(
        resolved: OnActivate.Navigate,
        speaks: Boolean,
        carries: Boolean,
    ): OnActivate? = if (speaks) OnActivate.SpeakThenNavigate(resolved, alsoAppends = carries) else null

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
