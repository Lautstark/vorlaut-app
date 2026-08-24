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
    )

    private val entries = ArrayList<Entry>()

    fun contents(): List<Entry> = entries.toList()

    /** What the bar shows. */
    fun displayed(): List<String> = entries.mapNotNull { it.display }

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
                entries += Entry(button.label, button.spokenText)
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
            OnActivate.Home, is OnActivate.Navigate, OnActivate.Disabled -> {
                null
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
