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
        // same way, applied the same way and ignored the same way. Since 1.5.0
        // its reach is the same too - see [modified].
        val speaks = button.bool("ext_lautstark_speak_on_navigate") == true

        // SPEC.md 7.3: load_board takes precedence over an action if a button
        // somehow carries both.
        button.obj("load_board")?.str("id")?.let {
            return modified(OnActivate.Navigate(it), speaks, carries)
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
            return resolved.singleOrNull()?.let { modified(it, speaks, carries) } ?: OnActivate.Sequence(resolved)
        }

        button.str("action")?.let { action ->
            // A disabled button appends nothing and says nothing either: doing
            // nothing at all is what disabled means, and modified() is not
            // reached for it.
            return IMPLEMENTED[action]?.let { modified(it, speaks, carries) }
                ?: disable(action, boardId, buttonId, warnings)
        }

        // SPEC.md 4.3. OBF cannot express "speak this now and leave the bar
        // alone", and interjections need it: Ouch!, stop that, a greeting.
        // Composing those into a sentence first defeats their purpose.
        if (button.bool("ext_lautstark_speak_immediately") == true) return OnActivate.SpeakImmediately

        return OnActivate.Append
    }

    /**
     * SPEC.md 7.3's two modifiers, applied where there is a navigation to apply
     * them to. Everything else is returned untouched, in silence: no warning
     * and no fault, because an appending button already appends and a
     * speak-immediately button carrying either flag meant something the format
     * has no way to say.
     *
     * **Both flags reach both navigating forms**, `load_board` and
     * `action: ":home"` alike, which is SPEC.md 1.5.0's change. 1.4.0 narrowed
     * the speaking one to `load_board` and this function was two, so that the
     * `:home` sites could not ask. They ask now, and there is one place that
     * decides what a modifier does to a navigation rather than two that have to
     * agree.
     *
     * Speaking wins where a button carries both, because [OnActivate.SpeakThenNavigate]
     * is the shape that can say "and it appends too" and the appending wrapper
     * is not - which is SPEC.md 7.3's "both modifiers on one button", where the
     * button appends its entry *and* speaks it, then navigates.
     */
    private fun modified(
        resolved: OnActivate,
        speaks: Boolean,
        carries: Boolean,
    ): OnActivate =
        when {
            resolved !is OnActivate.Navigation -> resolved
            speaks -> OnActivate.SpeakThenNavigate(resolved, alsoAppends = carries)
            carries -> OnActivate.AppendThenNavigate(resolved)
            else -> resolved
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
