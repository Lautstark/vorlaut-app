package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads a Lautstark board package (`.obz`) and returns what it contains.
 *
 * The order of work follows SPEC.md 11, which is normative: steps 1-7 may reject
 * the package whole, and from step 8 on every fault is button-level. That split
 * is the format's central idea — **strict about packages, lenient about buttons**
 * — and it is why a missing picture costs one button its picture rather than
 * costing a child their vocabulary.
 *
 * Nothing here throws. Every failure is a value, because the caller is a screen
 * somebody is looking at.
 */
object BoardPackageImporter {
    private const val OBF_FORMAT = "open-board-0.1"
    private const val MANIFEST = "manifest.json"

    fun import(bytes: ByteArray): ImportResult {
        // Steps 1-3: the container. Reading the central directory, checking every
        // member name for traversal, and refusing encryption, Zip64, unknown
        // compression methods and anything past the extraction bound all happen
        // inside ZipArchive.open - before a single member is inflated, as
        // SPEC.md 2 requires for the zip-slip case.
        val archive =
            try {
                ZipArchive.open(bytes)
            } catch (e: ZipArchive.UnsafePath) {
                return reject(RejectionCode.PATH_UNSAFE, e.message)
            } catch (e: ZipArchive.MalformedArchive) {
                return reject(RejectionCode.PACKAGE_UNREADABLE, e.message)
            } catch (e: Exception) {
                return reject(RejectionCode.PACKAGE_UNREADABLE, e.message)
            }

        return try {
            readPackage(archive)
        } catch (e: ZipArchive.MalformedArchive) {
            // A member listed in the directory whose data will not inflate. The
            // package cannot be verified whole, so it is refused whole rather
            // than salvaged in part.
            reject(RejectionCode.PACKAGE_UNREADABLE, e.message)
        }
    }

    private fun readPackage(archive: ZipArchive): ImportResult {
        val warnings = WarningList()

        // SPEC.md 9.4: a member name that is not NFC is a property of the archive
        // rather than of any one button, so this warning is package-scoped and is
        // recorded before anything button-shaped exists to hang it on.
        archive.denormalisedName?.let {
            warnings.add(
                WarningCode.PATH_NORMALIZATION,
                detail = "archive member name is not NFC: $it",
            )
        }

        // Step 4: the manifest.
        if (MANIFEST !in archive) {
            return reject(RejectionCode.MANIFEST_MISSING, "no $MANIFEST at the archive root")
        }
        val manifestBytes =
            archive.read(MANIFEST)
                ?: return reject(RejectionCode.MANIFEST_MISSING, "no $MANIFEST at the archive root")
        val manifest =
            Json.objectFrom(manifestBytes)
                ?: return reject(RejectionCode.MANIFEST_INVALID, "$MANIFEST is not a JSON object")

        val format =
            manifest.str("format")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no format")
        if (format != OBF_FORMAT) {
            return reject(RejectionCode.FORMAT_UNSUPPORTED, "format is $format, not $OBF_FORMAT")
        }

        // Step 5: the profile version, checked before the fields it governs.
        val versionText =
            manifest.str("ext_lautstark_spec_version")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no spec version")
        val specVersion =
            SpecVersion.parse(versionText)
                ?: return reject(RejectionCode.MANIFEST_INVALID, "spec version $versionText is not a version")
        if (specVersion.major > SpecVersion.IMPLEMENTED.major) {
            // A higher *minor* is accepted on purpose: SPEC.md 12 says minor
            // versions only add fields and actions, and SPEC.md 10.3 already says
            // what to do with the ones this importer does not know.
            return reject(
                RejectionCode.SPEC_VERSION_UNSUPPORTED,
                "package targets $specVersion; this importer implements ${SpecVersion.IMPLEMENTED}",
            )
        }

        // Step 6: the rest of the required manifest fields, then the licence.
        val paths = manifest.obj("paths")
        val boardPaths = paths?.obj("boards")?.stringMap().orEmpty()
        if (boardPaths.isEmpty()) {
            return reject(RejectionCode.MANIFEST_INVALID, "paths.boards is missing or empty")
        }
        val imagePaths = paths?.obj("images")?.stringMap().orEmpty()
        val soundPaths = paths?.obj("sounds")?.stringMap().orEmpty()

        val root =
            manifest.str("root")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no root")
        val packageId =
            manifest.str("ext_lautstark_package_id")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no package id")
        val packageName =
            manifest.str("ext_lautstark_package_name")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no package name")
        val modifiedText =
            manifest.str("ext_lautstark_modified")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no modified timestamp")
        val modified =
            try {
                Instant.parse(modifiedText)
            } catch (_: DateTimeParseException) {
                return reject(RejectionCode.MANIFEST_INVALID, "modified is not an RFC 3339 timestamp: $modifiedText")
            }
        val symbolSourceText =
            manifest.str("ext_lautstark_symbol_source")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no symbol source")
        val symbolSource =
            SymbolSource.fromWire(symbolSourceText)
                ?: return reject(RejectionCode.MANIFEST_INVALID, "symbol source $symbolSourceText is not one of arasaac, metacom, none")
        val redistributable =
            manifest.bool("ext_lautstark_redistributable")
                ?: return reject(RejectionCode.MANIFEST_INVALID, "manifest has no redistributable flag")

        // SPEC.md 5.2. METACOM is licensed per person, and baking its pixels into
        // a file that may then travel hands the collection over. This is a
        // licensing decision taken deliberately upstream; it is not to be relaxed
        // in an implementation.
        if (symbolSource == SymbolSource.METACOM && redistributable) {
            return reject(
                RejectionCode.LICENCE_INCONSISTENT,
                "a metacom package must set redistributable to false",
            )
        }

        // Step 7: every board, the root, and every grid. All package-level.
        val rootBoardId =
            boardPaths.entries.firstOrNull { ZipArchive.normalise(it.value) == ZipArchive.normalise(root) }?.key
                ?: return reject(RejectionCode.ROOT_MISSING, "root $root is not a value in paths.boards")
        if (root !in archive) {
            return reject(RejectionCode.ROOT_MISSING, "root board $root is not in the archive")
        }

        val documents = LinkedHashMap<String, JsonObject>()
        for ((boardId, path) in boardPaths) {
            val boardBytes =
                archive.read(path)
                    ?: return reject(RejectionCode.BOARD_INVALID, "board $boardId is not in the archive at $path")
            val document =
                Json.objectFrom(boardBytes)
                    ?: return reject(RejectionCode.BOARD_INVALID, "board $boardId at $path is not a JSON object")
            val grid =
                document.obj("grid")
                    ?: return reject(RejectionCode.BOARD_INVALID, "board $boardId has no grid")
            val rows =
                grid.int("rows")
                    ?: return reject(RejectionCode.BOARD_INVALID, "board $boardId has no grid.rows")
            val columns =
                grid.int("columns")
                    ?: return reject(RejectionCode.BOARD_INVALID, "board $boardId has no grid.columns")
            val order = grid.arr("order")
            // SPEC.md 7.1: a grid that does not match its own rows x columns is a
            // package-level fault, not a button-level one. A viewer guessing at the
            // structure would put buttons somewhere other than where the builder
            // put them, and for someone navigating by position that is worse than
            // no board at all.
            if (rows < 0 || columns < 0 || order == null || order.size != rows) {
                return reject(
                    RejectionCode.GRID_MALFORMED,
                    "board $boardId declares ${rows}x$columns but order has ${order?.size ?: 0} rows",
                )
            }
            order.forEachIndexed { index, row ->
                val cells = row as? kotlinx.serialization.json.JsonArray
                if (cells == null || cells.size != columns) {
                    return reject(
                        RejectionCode.GRID_MALFORMED,
                        "board $boardId row $index has ${cells?.size ?: 0} cells, not $columns",
                    )
                }
            }
            documents[boardId] = document
        }

        // Step 9: resolve each board's content. Everything from here is
        // button-level: the package is imported whatever is found.
        val boards =
            documents.map { (boardId, document) ->
                readBoard(boardId, document, archive, imagePaths, soundPaths, warnings)
            }

        return ImportResult.Accepted(
            boardPackage =
                BoardPackage(
                    id = packageId,
                    name = packageName,
                    modified = modified,
                    symbolSource = symbolSource,
                    redistributable = redistributable,
                    ttsVoice = manifest.str("ext_lautstark_tts_voice"),
                    // SPEC.md 4.1: optional, false by default, and a hint an
                    // importer must never fail over — so a value that is not a
                    // boolean is treated as absent rather than rejected.
                    firstColumnGap = manifest.bool("ext_lautstark_first_column_gap") ?: false,
                    // SPEC.md 4.1 and 7.5: optional, 0 by default, clamped at
                    // the ceiling, and — like the gap above — never a reason to
                    // fail an import. Sanitised once here so that nothing
                    // downstream has to know the rule; see pressTiming.
                    holdTimeMs = pressTiming(manifest.long("ext_lautstark_hold_time_ms")),
                    releaseTimeMs = pressTiming(manifest.long("ext_lautstark_release_time_ms")),
                    specVersion = specVersion,
                    rootBoardId = rootBoardId,
                    boards = boards,
                ),
            warnings = warnings.ordered(rootBoardId, boards),
        )
    }

    private fun readBoard(
        boardId: String,
        document: JsonObject,
        archive: ZipArchive,
        imagePaths: Map<String, String>,
        soundPaths: Map<String, String>,
        warnings: WarningList,
    ): Board {
        val grid = document.obj("grid")!!
        val rows = grid.int("rows")!!
        val columns = grid.int("columns")!!
        val cells =
            grid.arr("order")!!.map { row ->
                (row as kotlinx.serialization.json.JsonArray).map { it.asStringOrNull() }
            }

        val color =
            document.str("ext_lautstark_board_color")?.let { raw ->
                Colors.parse(raw) ?: run {
                    warnings.add(WarningCode.COLOR_UNPARSEABLE, boardId, detail = "board colour $raw did not parse")
                    null
                }
            }

        val declared = document.arr("buttons").orEmpty().mapNotNull { it.asObject() }
        val byId = declared.mapNotNull { button -> button.str("id")?.let { it to button } }.toMap()
        val images = entriesById(document.arr("images"))
        val sounds = entriesById(document.arr("sounds"))

        // Grid order, row-major, is the visiting order - which is what makes the
        // warning list deterministic without needing to be sorted afterwards.
        val buttons = ArrayList<Button>()
        for (row in cells) {
            for (cellId in row) {
                if (cellId == null) continue
                val declaredButton = byId[cellId]
                if (declaredButton == null) {
                    // SPEC.md 7.1: an id in order with no matching button leaves the
                    // cell empty and warns. Nothing survives, so nothing is added.
                    warnings.add(
                        WarningCode.BUTTON_MISSING,
                        boardId,
                        cellId,
                        "grid names button $cellId, which the board does not define",
                    )
                    continue
                }
                // SPEC.md 7.2: a hidden button is not rendered and its cell stays
                // empty. A button appearing in buttons[] but not in order is not
                // rendered either, which is why this walks the grid and not the list.
                if (declaredButton.bool("hidden") == true) continue
                buttons += readButton(declaredButton, cellId, boardId, archive, images, sounds, imagePaths, soundPaths, warnings)
                    ?: continue
            }
        }

        return Board(
            id = boardId,
            name = document.str("name") ?: boardId,
            locale = document.str("locale"),
            rows = rows,
            columns = columns,
            color = color,
            cells = cells,
            buttons = buttons,
        )
    }

    private fun entriesById(array: kotlinx.serialization.json.JsonArray?): Map<String, JsonObject> =
        array
            .orEmpty()
            .mapNotNull { it.asObject() }
            .mapNotNull { entry -> entry.str("id")?.let { it to entry } }
            .toMap()

    @Suppress("LongParameterList")
    private fun readButton(
        button: JsonObject,
        buttonId: String,
        boardId: String,
        archive: ZipArchive,
        images: Map<String, JsonObject>,
        sounds: Map<String, JsonObject>,
        imagePaths: Map<String, String>,
        soundPaths: Map<String, String>,
        warnings: WarningList,
    ): Button? {
        val label = button.str("label")
        val vocalization = button.str("vocalization")
        val imageId = button.str("image_id")

        // SPEC.md 7.2: a button with no label, no vocalization and no image
        // renders as an empty cell. There is nothing to show and nothing to say.
        if (label == null && vocalization == null && imageId == null) return null

        val background = button.str("background_color").let { readColor(it, boardId, buttonId, warnings) }
        val border = button.str("border_color").let { readColor(it, boardId, buttonId, warnings) }

        val onActivate = Actions.classify(button, boardId, buttonId, warnings)

        // SPEC.md 9.2: an unsupported action still leaves label, colour and image
        // standing - the button is dead, not blank.
        val image = resolveImage(imageId, boardId, buttonId, archive, images, imagePaths, warnings)

        // A button only carries audio if pressing it makes a sound of its own.
        // :speak speaks the bar, :home navigates - neither has audio to resolve,
        // and a disabled button never gets to make one.
        //
        // A carrying button does: it appends, and appending is what utters. The
        // navigation afterwards changes nothing about what was said, so it earns
        // the same clip as the word button it also is. Without this it opens the
        // next board in silence, which is the quiet half of the failure - the
        // board still changes, so nothing looks broken.
        //
        // A speak-on-navigate button does too, and for it the clip is not a
        // nicety but the entire point: it carries the flag *in order to* be
        // heard on the way through, so resolving no audio would leave it
        // indistinguishable from the plain navigation beside it.
        val speaks =
            onActivate == OnActivate.Append ||
                onActivate == OnActivate.SpeakImmediately ||
                onActivate is OnActivate.AppendThenNavigate ||
                onActivate is OnActivate.SpeakThenNavigate
        val sound =
            if (speaks) {
                resolveSound(button.str("sound_id"), boardId, buttonId, archive, sounds, soundPaths, warnings)
            } else {
                SoundOutcome(audio = null, degraded = false)
            }

        val state =
            when {
                onActivate == OnActivate.Disabled -> ButtonState.DISABLED
                image.degraded || sound.degraded -> ButtonState.DEGRADED
                else -> ButtonState.NORMAL
            }

        return Button(
            id = buttonId,
            label = label,
            vocalization = vocalization,
            onActivate = onActivate,
            imagePath = image.path,
            audio = sound.audio,
            state = state,
            backgroundColor = background,
            borderColor = border,
        )
    }

    private fun readColor(
        raw: String?,
        boardId: String,
        buttonId: String,
        warnings: WarningList,
    ): String? {
        if (raw == null) return null
        return Colors.parse(raw) ?: run {
            // SPEC.md 7.2: an unparseable colour falls back to the viewer default
            // and warns. It is not a fault - nothing about the button is missing.
            warnings.add(WarningCode.COLOR_UNPARSEABLE, boardId, buttonId, "colour $raw did not parse")
            null
        }
    }

    private class ImageOutcome(
        val path: String?,
        val degraded: Boolean,
    )

    private class SoundOutcome(
        val audio: AudioSource?,
        val degraded: Boolean,
    )

    @Suppress("LongParameterList", "ReturnCount")
    private fun resolveImage(
        imageId: String?,
        boardId: String,
        buttonId: String,
        archive: ZipArchive,
        images: Map<String, JsonObject>,
        imagePaths: Map<String, String>,
        warnings: WarningList,
    ): ImageOutcome {
        if (imageId == null) return ImageOutcome(path = null, degraded = false)
        val entry = images[imageId]
        val fromManifest = imagePaths[imageId]
        val fromBoard = entry?.str("path")

        // SPEC.md 3: `paths` is the authority on where a member lives. Where the
        // board's own images[].path disagrees, paths wins and the disagreement is
        // worth telling the builder about.
        if (fromManifest != null && fromBoard != null &&
            ZipArchive.normalise(fromManifest) != ZipArchive.normalise(fromBoard)
        ) {
            warnings.add(
                WarningCode.PATH_CONFLICT,
                boardId,
                buttonId,
                "paths.images says $fromManifest, the board says $fromBoard; paths wins",
            )
        }
        val path = fromManifest ?: fromBoard

        // SPEC.md 5: the viewer resolves nothing. A url or data_url alongside a
        // usable path is ignored and warned about, because the alternative - a
        // silently pictureless button - is what the caregiver would have to
        // diagnose later.
        if (path != null && (entry?.str("url") != null || entry?.str("data_url") != null)) {
            warnings.add(
                WarningCode.IMAGE_REFERENCE_IGNORED,
                boardId,
                buttonId,
                "image $imageId carries a url or data_url; the baked file is used instead",
            )
        }

        if (path == null) {
            // Covers the symbol-without-path case too: SPEC.md 5 makes an entry
            // carrying `symbol` but no usable path a button-level fault, since the
            // symbol library is not shipped and never will be.
            warnings.add(WarningCode.IMAGE_MISSING, boardId, buttonId, "image $imageId resolves to no file")
            return ImageOutcome(path = null, degraded = true)
        }

        val bytes = archive.read(path)
        if (bytes == null) {
            warnings.add(WarningCode.IMAGE_MISSING, boardId, buttonId, "image $imageId names $path, which the package does not contain")
            return ImageOutcome(path = null, degraded = true)
        }

        val dimensions = Media.dimensionsOf(bytes)
        if (dimensions == null) {
            warnings.add(WarningCode.IMAGE_UNDECODABLE, boardId, buttonId, "image $path is not a PNG or JPEG this viewer can read")
            return ImageOutcome(path = null, degraded = true)
        }
        if (dimensions.exceedsCap) {
            // Refused, not downscaled. Downscaling would make the cap advisory,
            // and the memory it protects is spent at decode time - before any
            // downscale could help.
            val declared = entry?.int("width")?.let { w -> entry.int("height")?.let { h -> ", declared ${w}x$h" } }.orEmpty()
            warnings.add(
                WarningCode.IMAGE_OVERSIZED,
                boardId,
                buttonId,
                "$dimensions exceeds ${Media.MAX_IMAGE_EDGE}x${Media.MAX_IMAGE_EDGE}$declared",
            )
            return ImageOutcome(path = null, degraded = true)
        }
        return ImageOutcome(path = ZipArchive.normalise(path), degraded = false)
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun resolveSound(
        soundId: String?,
        boardId: String,
        buttonId: String,
        archive: ZipArchive,
        sounds: Map<String, JsonObject>,
        soundPaths: Map<String, String>,
        warnings: WarningList,
    ): SoundOutcome {
        // SPEC.md 9.2, and the distinction fixture `missing-audio` exists to
        // enforce: a button with no sound at all is **not** degraded. A board built
        // without recorded audio is a normal board and TTS is its designed path.
        // Only audio that was promised and is missing marks a button - an importer
        // that marks every TTS button degraded puts a fault marker on every button
        // of every TTS-only board and thereby makes the marker useless.
        if (soundId == null) return SoundOutcome(AudioSource.Tts, degraded = false)

        val entry = sounds[soundId]
        val path = soundPaths[soundId] ?: entry?.str("path")
        if (path == null) {
            warnings.add(WarningCode.SOUND_MISSING, boardId, buttonId, "sound $soundId resolves to no file")
            return SoundOutcome(AudioSource.Tts, degraded = true)
        }
        val bytes = archive.read(path)
        if (bytes == null) {
            warnings.add(WarningCode.SOUND_MISSING, boardId, buttonId, path)
            return SoundOutcome(AudioSource.Tts, degraded = true)
        }
        return when (val audio = Media.inspectAudio(bytes)) {
            is Media.Audio.Undecodable -> {
                warnings.add(WarningCode.SOUND_UNDECODABLE, boardId, buttonId, "sound $path is not Ogg Opus or 16 kHz mono PCM WAV")
                SoundOutcome(AudioSource.Tts, degraded = true)
            }

            is Media.Audio.DurationUnknown -> {
                SoundOutcome(AudioSource.Recorded(ZipArchive.normalise(path)), degraded = false)
            }

            is Media.Audio.Playable -> {
                if (audio.seconds > Media.MAX_AUDIO_SECONDS) {
                    warnings.add(
                        WarningCode.SOUND_TOO_LONG,
                        boardId,
                        buttonId,
                        "sound $path runs ${audio.seconds}s, over ${Media.MAX_AUDIO_SECONDS}s",
                    )
                    SoundOutcome(AudioSource.Tts, degraded = true)
                } else {
                    SoundOutcome(AudioSource.Recorded(ZipArchive.normalise(path)), degraded = false)
                }
            }
        }
    }

    private fun reject(
        code: RejectionCode,
        detail: String?,
    ): ImportResult.Rejected = ImportResult.Rejected(code, detail ?: code.wireName)
}

/**
 * Collects warnings, then puts them in the order SPEC.md 9.5 requires.
 *
 * That order is part of the format: two importers reading the same package must
 * produce the same sequence, and the same importer must produce it again on
 * re-import. The reason is not fussiness. This list is caregiver-facing and it is
 * how somebody finds out which buttons on a child's device are incomplete — if it
 * reshuffles between imports, a person comparing it against what they saw last
 * week cannot tell a new fault from a moved line, and the list stops being read.
 *
 * Collecting in visit order and sorting at the end, rather than emitting in the
 * final order, keeps the pipeline free to meet warnings whenever it meets them.
 */
internal class WarningList {
    private val collected = ArrayList<ImportWarning>()

    fun add(
        code: WarningCode,
        boardId: String? = null,
        buttonId: String? = null,
        detail: String,
    ) {
        collected += ImportWarning(code, boardId, buttonId, detail)
    }

    /**
     * SPEC.md 9.5, in its four steps:
     *
     * 1. package-scoped warnings first (`board` null);
     * 2. then board by board — **the root board first**, then every other board id
     *    in code point order. Root first because it is the page the user actually
     *    opens; code point rather than `paths.boards` order because an importer
     *    should not have to rely on JSON object key order;
     * 3. within a board, board-scoped warnings (`button` null) first, then per
     *    button in `grid.order` row-major order — reading order, not the order
     *    buttons happen to appear in `buttons[]`;
     * 4. ties on one button in code point order of `code`.
     *
     * The sort is stable, so anything the rules leave genuinely equal keeps the
     * order it was met in and stays reproducible.
     */
    fun ordered(
        rootBoardId: String,
        boards: List<Board>,
    ): List<ImportWarning> {
        // Reading order of each board's cells. First occurrence wins, so a button
        // id repeated in the grid is ranked where it is first met.
        val readingOrder: Map<String, Map<String, Int>> =
            boards.associate { board ->
                val positions = LinkedHashMap<String, Int>()
                board.cells.flatten().forEachIndexed { index, id ->
                    if (id != null) positions.putIfAbsent(id, index)
                }
                board.id to positions
            }

        return collected.sortedWith(
            compareBy<ImportWarning> { if (it.boardId == null) 0 else 1 }
                .thenBy { if (it.boardId == null || it.boardId == rootBoardId) 0 else 1 }
                .thenBy { it.boardId.orEmpty() }
                .thenBy { if (it.buttonId == null) 0 else 1 }
                .thenBy { readingOrder[it.boardId]?.get(it.buttonId) ?: Int.MAX_VALUE }
                .thenBy { it.code.wireName },
        )
    }
}
