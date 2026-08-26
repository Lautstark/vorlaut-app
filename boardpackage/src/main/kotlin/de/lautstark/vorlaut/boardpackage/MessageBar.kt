package de.lautstark.vorlaut.boardpackage

/**
 * The message bar (SPEC.md 7.3). Buttons compose a sentence which is then spoken.
 *
 * The bar holds **entries, not words**. One press contributes one entry whatever
 * its length, which is why `:backspace` removes an entry rather than a character:
 * a button whose vocalization is "einen Apfel" arrived as one press and has to
 * leave as one.
 *
 * This is domain logic, not rendering — it decides what the bar contains and what
 * would be spoken, and says nothing about how either is shown or synthesised.
 */
class MessageBar {
    /**
     * One press. [display] is the button's label and [spoken] its vocalization;
     * SPEC.md 7.3 says the bar renders the one and speaks the other, so an entry
     * has to carry both.
     */
    data class Entry(
        val display: String?,
        val spoken: String?,
        /**
         * The symbol that was pressed, and the tint it was pressed on.
         *
         * The bar renders pictures, not text: the person reading a sentence
         * back is often the person who cannot read it, and the tiles they
         * touched are the only form of it they can check. So an entry has to
         * remember which tile it came from, not only what it will say.
         */
        val imagePath: String? = null,
        val tint: String? = null,
        /**
         * The recording the press came from, if it had one.
         *
         * The bar remembers it for the same reason it remembers the picture:
         * `:speak` says the whole sentence, and a sentence whose words were
         * recorded should be said in the voice they were recorded in rather
         * than handed to the device's own. Which of the two happens is the
         * viewer's business — this only keeps the path so the choice is
         * available at all.
         */
        val soundPath: String? = null,
    )

    private val entries = ArrayList<Entry>()

    fun contents(): List<Entry> = entries.toList()

    /** What the bar shows. */
    fun displayed(): List<String> = entries.mapNotNull { it.display }

    /**
     * Takes back the last press — the bar's own undo control, which is the same
     * act as `:backspace` reached from somewhere else.
     */
    fun removeLast() {
        entries.removeLastOrNull()
    }

    /** What `:speak` would say. */
    fun spokenText(): String = entries.mapNotNull { it.spoken }.joinToString(" ")

    /**
     * Applies one button press and returns what should be spoken, or null for
     * silence.
     */
    fun press(button: Button): String? =
        when (button.onActivate) {
            // The default and the common case.
            OnActivate.Append -> {
                entries +=
                    Entry(
                        // SPEC.md 7.3: an entry shows its vocalization, falling
                        // back to the label when the button has none. A button
                        // labelled "Apfel" that says "einen Apfel" puts the
                        // phrase in the bar, so the bar reads as the sentence it
                        // is about to speak rather than as the row of keys that
                        // built it. This said `button.label`, which is the
                        // reading an earlier draft of 7.3 had and the fixture
                        // never checked - it asserts `spoken` and nothing else.
                        display = button.spokenText ?: button.label,
                        spoken = button.spokenText,
                        imagePath = button.imagePath,
                        soundPath = (button.audio as? AudioSource.Recorded)?.path,
                        tint = button.backgroundColor,
                    )
                null
            }

            // Speaks at once and must not touch the bar.
            OnActivate.SpeakImmediately -> {
                button.spokenText
            }

            // Speaks the whole bar and leaves it standing. :speak does not clear.
            OnActivate.SpeakBar -> {
                spokenText().takeIf { it.isNotEmpty() }
            }

            OnActivate.Clear -> {
                entries.clear()
                null
            }

            OnActivate.Backspace -> {
                entries.removeLastOrNull()
                null
            }

            // Navigation must not touch the bar, and a dead button does nothing.
            is OnActivate.Navigation, OnActivate.Disabled -> {
                null
            }

            // SPEC.md 7.3's append-on-navigate: the same entry an ordinary press
            // would leave, and then a navigation this class knows nothing about.
            // Delegated rather than repeated, so there is one place that decides
            // what an entry holds - the bug that put labels in the bar instead of
            // vocalizations was one such decision written twice.
            is OnActivate.AppendThenNavigate -> {
                press(button.copy(onActivate = OnActivate.Append))
            }

            is OnActivate.Sequence -> {
                // SPEC.md 7.4 defines no semantics for several implemented actions
                // in one button, and no fixture exercises it. Running them in order
                // is the only reading that does not discard what the builder wrote.
                var spoken: String? = null
                button.onActivate.actions.forEach { step ->
                    spoken = press(button.copy(onActivate = step)) ?: spoken
                }
                spoken
            }
        }
}
