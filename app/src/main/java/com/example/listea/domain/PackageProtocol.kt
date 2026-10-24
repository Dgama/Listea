package com.example.listea.domain

import java.security.MessageDigest

/**
 * `listea.package/1`, read and planned without touching Android.
 *
 * A Package is a directory whose manifest names Prompts. This file turns the bytes a host has
 * already located into a [PackagePlan]: what would be written, what is merely diagnosed, and the
 * fingerprint that tells a later import whether anything changed. It performs no I/O itself — the
 * host supplies the manifest, the set of member paths it saw, and a way to read a referenced
 * description — which keeps every rejection rule testable on the JVM and keeps a partially read
 * Package from being written.
 *
 * The division of strictness is deliberate and follows the protocol:
 *
 * - A bad **envelope** — protocol, keys, revision, title, the manifest as a whole — rejects the
 *   whole Package. Nothing about it is written.
 * - A bad **Prompt** or **Resource** is diagnosed and kept out of the plan while the valid rest is
 *   planned. A Prompt whose description parsed but whose kind has no Viewer is still planned with
 *   its payload, marked unavailable: a later build can make it reviewable without a re-import.
 *
 * The fingerprint is a SHA-256 over the canonical manifest and the canonical description of every
 * Prompt entry, in sorted Prompt-key order. It covers no Asset or Resource bytes; a photo changing
 * without a revision bump is not this version's concern.
 */
object PackageProtocol {
    const val PROTOCOL = "listea.package/1"

    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_DESCRIPTION_BYTES = 64 * 1024
    const val MAX_PROMPTS = 2_000
    const val MAX_RESOURCES = 5_000
    const val MAX_ASSET_USES = 200
    const val MAX_RESOURCE_USES = 50
    const val MAX_OPTIONS = 100
    const val MAX_MEMBER_PATH = 512
    const val MAX_TITLE = 200
    const val MAX_OPTION_LABEL = 200
    const val MAX_PACKAGE_TITLE = 200
    const val MAX_DISCOVERY_DEPTH = 4
    const val MAX_PACKAGES_PER_SCOPE = 100

    private val KEY = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val RESOURCE_KINDS = setOf("image", "audio", "video", "pdf", "other")
    private val TOP_FIELDS = setOf("protocol", "packageKey", "revision", "title", "prompts", "resources")
    private val PROMPT_FIELDS = setOf(
        "promptKey", "kind", "schema", "response", "title", "description", "assets", "resources"
    )
    private val ASSET_FIELDS = setOf("member", "name")
    private val RESOURCE_USE_FIELDS = setOf("resourceKey", "required")
    private val RESOURCE_FIELDS = setOf("resourceKey", "kind", "member")

    /**
     * The description members the host must read. It should read only these: a Package's photos
     * are not part of the manifest, and reading them to plan would make import cost proportional
     * to media size.
     */
    fun descriptionMembers(manifestBytes: ByteArray): List<String>? {
        val root = runCatching { Json.parse(manifestBytes) }.getOrNull() as? JsonValue.Obj
            ?: return null
        val prompts = root["prompts"] as? JsonValue.Arr ?: return null
        return prompts.items.mapNotNull { entry ->
            (entry as? JsonValue.Obj)?.get("description")?.asStringOrNull()
                ?.takeIf { isSafeMember(it) }
        }
    }

    fun plan(
        snapshot: PackageSnapshot,
        viewerSupports: (kind: String, schema: Int) -> Boolean = ::supportsKind
    ): PackagePlan {
        val diagnostics = mutableListOf<PackageDiagnostic>()

        fun packageProblem(code: String, message: String) {
            diagnostics += PackageDiagnostic(DiagnosticScope.PACKAGE, null, code, message)
        }

        fun rejected(): PackagePlan =
            PackagePlan(snapshot.rootRelativePath, null, null, null, emptyList(), emptyList(), diagnostics)

        val bytes = snapshot.manifestBytes
            ?: run {
                packageProblem("manifest-missing", "The Package has no .listea/manifest.json.")
                return rejected()
            }
        if (bytes.size > MAX_MANIFEST_BYTES) {
            packageProblem(
                "limit-exceeded",
                "The manifest is ${bytes.size} bytes; the limit is $MAX_MANIFEST_BYTES."
            )
            return rejected()
        }

        val root = try {
            Json.parse(bytes)
        } catch (e: JsonParseException) {
            packageProblem(e.code, e.message ?: "The manifest could not be read.")
            return rejected()
        }
        val manifest = root as? JsonValue.Obj
            ?: run {
                packageProblem("not-object", "The manifest must be a JSON object.")
                return rejected()
            }

        fun topString(name: String): String? {
            val value = manifest[name]
            if (value == null) {
                packageProblem("missing-field", "The manifest is missing \"$name\".")
                return null
            }
            val text = value.asStringOrNull()
            if (text == null) {
                packageProblem("invalid-field", "\"$name\" must be a string.")
                return null
            }
            return text
        }

        for (key in manifest.keys - TOP_FIELDS) {
            packageProblem("unknown-field", "The manifest has an unknown field \"$key\".")
        }

        val protocol = topString("protocol")
        if (protocol != null && protocol != PROTOCOL) {
            packageProblem(
                "protocol-unknown",
                "The Package declares \"$protocol\"; this build reads $PROTOCOL."
            )
        }

        val packageKey = topString("packageKey")
        if (packageKey != null && !isKey(packageKey)) {
            packageProblem("key-invalid", "\"$packageKey\" is not a valid packageKey.")
        }

        val revisionValue = manifest["revision"]
        val revision = revisionValue?.asIntOrNull()
        if (revisionValue == null) {
            packageProblem("missing-field", "The manifest is missing \"revision\".")
        } else if (revision == null || revision < 1) {
            packageProblem("invalid-field", "\"revision\" must be an integer of at least 1.")
        }

        val title = topString("title")
        if (title != null && title.length !in 1..MAX_PACKAGE_TITLE) {
            packageProblem(
                "invalid-field",
                "The Package title must be 1–$MAX_PACKAGE_TITLE characters."
            )
        }

        val promptValues = manifest["prompts"]
        if (promptValues == null) {
            packageProblem("missing-field", "The manifest is missing \"prompts\".")
        } else if (promptValues !is JsonValue.Arr) {
            packageProblem("invalid-field", "\"prompts\" must be an array.")
        } else if (promptValues.items.size > MAX_PROMPTS) {
            packageProblem(
                "limit-exceeded",
                "The Package has ${promptValues.items.size} Prompts; the limit is $MAX_PROMPTS."
            )
        }

        val resourceValues = manifest["resources"]
        if (resourceValues != null && resourceValues !is JsonValue.Arr) {
            packageProblem("invalid-field", "\"resources\" must be an array when present.")
        } else if (resourceValues is JsonValue.Arr && resourceValues.items.size > MAX_RESOURCES) {
            packageProblem(
                "limit-exceeded",
                "The Package has ${resourceValues.items.size} Resources; the limit is $MAX_RESOURCES."
            )
        }

        if (diagnostics.any { it.scope == DiagnosticScope.PACKAGE }) return rejected()

        // The envelope is sound from here. Prompt and Resource problems are diagnosed individually.
        val resources = mutableListOf<PlannedPackageResource>()
        val resourceByKey = mutableMapOf<String, PlannedPackageResource>()
        var unsafeResourceMember = false

        (resourceValues as? JsonValue.Arr)?.items?.forEachIndexed { index, value ->
            val entry = value as? JsonValue.Obj
            if (entry == null) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.RESOURCE, null, "invalid-field",
                    "Resource #${index + 1} must be an object."
                )
                return@forEachIndexed
            }
            val key = entry["resourceKey"]?.asStringOrNull()
            if (key == null || !isKey(key)) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.RESOURCE, key, "key-invalid",
                    "Resource #${index + 1} has no valid resourceKey."
                )
                return@forEachIndexed
            }
            if (resourceByKey.containsKey(key)) {
                packageProblem("duplicate-key", "\"$key\" names more than one Resource.")
                return@forEachIndexed
            }
            if ((entry.keys - RESOURCE_FIELDS).isNotEmpty()) {
                val unknown = (entry.keys - RESOURCE_FIELDS).joinToString(", ")
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.RESOURCE, key, "unknown-field",
                    "Resource \"$key\" has unknown fields: $unknown."
                )
            }
            val kind = entry["kind"]?.asStringOrNull()
            val member = entry["member"]?.asStringOrNull()
            if (kind !in RESOURCE_KINDS) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.RESOURCE, key, "invalid-field",
                    "Resource \"$key\" has an unknown kind \"$kind\"."
                )
                return@forEachIndexed
            }
            if (member == null || !isSafeMember(member)) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.RESOURCE, key, "unsafe-member",
                    "Resource \"$key\" has a member reference this build cannot follow."
                )
                unsafeResourceMember = true
                return@forEachIndexed
            }
            val present = member in snapshot.presentMembers
            if (!present) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.RESOURCE, key, "resource-missing",
                    "Resource \"$key\" names \"$member\", which is not in the Package."
                )
            }
            val planned = PlannedPackageResource(key, kind!!, member, present)
            resources += planned
            resourceByKey[key] = planned
        }

        if (unsafeResourceMember) return rejected()

        val prompts = mutableListOf<PlannedPrompt>()
        val descriptionCanonical = mutableMapOf<String, String>()
        val seenPromptKeys = mutableSetOf<String>()

        (promptValues as JsonValue.Arr).items.forEachIndexed { index, value ->
            val entry = value as? JsonValue.Obj
            if (entry == null) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.PROMPT, null, "invalid-field",
                    "Prompt #${index + 1} must be an object."
                )
                return@forEachIndexed
            }
            val promptKey = entry["promptKey"]?.asStringOrNull()
            if (promptKey == null || !isKey(promptKey)) {
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.PROMPT, promptKey, "key-invalid",
                    "Prompt #${index + 1} has no valid promptKey."
                )
                return@forEachIndexed
            }
            if (!seenPromptKeys.add(promptKey)) {
                packageProblem("duplicate-key", "\"$promptKey\" names more than one Prompt.")
                return@forEachIndexed
            }

            val promptDiagnostics = mutableListOf<PackageDiagnostic>()
            var unavailable: String? = null
            fun promptProblem(code: String, message: String) {
                promptDiagnostics += PackageDiagnostic(
                    DiagnosticScope.PROMPT, promptKey, code, message
                )
                if (unavailable == null) unavailable = code
            }

            val unknownFields = entry.keys - PROMPT_FIELDS
            if (unknownFields.isNotEmpty()) {
                promptProblem(
                    "unknown-field",
                    "Prompt \"$promptKey\" has unknown fields: ${unknownFields.joinToString(", ")}."
                )
            }

            val kind = entry["kind"]?.asStringOrNull()
            if (kind == null || !isKey(kind)) {
                promptProblem("invalid-field", "Prompt \"$promptKey\" has an invalid kind.")
            }
            val schemaValue = entry["schema"]
            val schema = schemaValue?.asIntOrNull()
            if (schema == null || schema < 1) {
                promptProblem("invalid-field", "Prompt \"$promptKey\" has an invalid schema.")
            }
            val responseWire = entry["response"]?.asStringOrNull()
            val response = responseWire?.let(ResponseContract::fromWire)
            if (response == null) {
                promptProblem(
                    "invalid-field",
                    "Prompt \"$promptKey\" has an unknown response contract \"$responseWire\"."
                )
            }
            val promptTitle = entry["title"]?.asStringOrNull()
            if (promptTitle == null || promptTitle.length !in 1..MAX_TITLE) {
                promptProblem(
                    "invalid-field",
                    "Prompt \"$promptKey\" needs a title of 1–$MAX_TITLE characters."
                )
            }

            val descriptionMember = entry["description"]?.asStringOrNull()
            if (descriptionMember == null) {
                promptProblem("missing-field", "Prompt \"$promptKey\" has no description.")
            } else if (!isSafeMember(descriptionMember)) {
                promptProblem(
                    "unsafe-member",
                    "Prompt \"$promptKey\" has a description path this build cannot follow."
                )
            } else if (!descriptionMember.endsWith(".json")) {
                promptProblem(
                    "invalid-field",
                    "Prompt \"$promptKey\"'s description must be a .json member."
                )
            }

            val assets = mutableListOf<PlannedPromptAsset>()
            val assetValues = entry["assets"]
            if (assetValues != null && assetValues !is JsonValue.Arr) {
                promptProblem("invalid-field", "Prompt \"$promptKey\"'s assets must be an array.")
            } else if (assetValues is JsonValue.Arr) {
                if (assetValues.items.size > MAX_ASSET_USES) {
                    promptProblem(
                        "limit-exceeded",
                        "Prompt \"$promptKey\" has more than $MAX_ASSET_USES Assets."
                    )
                } else {
                    assetValues.items.forEachIndexed assetUse@{ assetIndex, assetValue ->
                        val asset = assetValue as? JsonValue.Obj
                        if (asset == null) {
                            promptProblem(
                                "invalid-field",
                                "Prompt \"$promptKey\" Asset #${assetIndex + 1} must be an object."
                            )
                            return@assetUse
                        }
                        val member = asset["member"]?.asStringOrNull()
                        if (member == null || !isSafeMember(member)) {
                            promptProblem(
                                "asset-unsafe",
                                "Prompt \"$promptKey\" Asset #${assetIndex + 1} has a member this build cannot follow."
                            )
                            return@assetUse
                        }
                        val unknownAsset = asset.keys - ASSET_FIELDS
                        if (unknownAsset.isNotEmpty()) {
                            promptProblem(
                                "unknown-field",
                                "Prompt \"$promptKey\" Asset #${assetIndex + 1} has unknown fields."
                            )
                        }
                        val name = asset["name"]?.asStringOrNull()
                            ?: member.substringAfterLast('/')
                        if (name.length !in 1..MAX_TITLE) {
                            promptProblem(
                                "invalid-field",
                                "Prompt \"$promptKey\" Asset #${assetIndex + 1} has an invalid name."
                            )
                            return@assetUse
                        }
                        val present = member in snapshot.presentMembers
                        if (!present) {
                            promptProblem(
                                "asset-missing",
                                "Prompt \"$promptKey\" Asset \"$member\" is not in the Package."
                            )
                        }
                        assets += PlannedPromptAsset(member, name, present)
                    }
                }
            }

            val resourceUses = mutableListOf<PlannedPromptResource>()
            val useValues = entry["resources"]
            if (useValues != null && useValues !is JsonValue.Arr) {
                promptProblem(
                    "invalid-field",
                    "Prompt \"$promptKey\"'s resources must be an array."
                )
            } else if (useValues is JsonValue.Arr) {
                if (useValues.items.size > MAX_RESOURCE_USES) {
                    promptProblem(
                        "limit-exceeded",
                        "Prompt \"$promptKey\" has more than $MAX_RESOURCE_USES Resource uses."
                    )
                } else {
                    val used = mutableSetOf<String>()
                    useValues.items.forEachIndexed resourceUse@{ useIndex, useValue ->
                        val use = useValue as? JsonValue.Obj
                        if (use == null) {
                            promptProblem(
                                "invalid-field",
                                "Prompt \"$promptKey\" Resource use #${useIndex + 1} must be an object."
                            )
                            return@resourceUse
                        }
                        val resourceKey = use["resourceKey"]?.asStringOrNull()
                        if (resourceKey == null || !isKey(resourceKey)) {
                            promptProblem(
                                "invalid-field",
                                "Prompt \"$promptKey\" Resource use #${useIndex + 1} has no valid key."
                            )
                            return@resourceUse
                        }
                        if (!used.add(resourceKey)) {
                            promptProblem(
                                "duplicate-key",
                                "Prompt \"$promptKey\" uses \"$resourceKey\" twice."
                            )
                            return@resourceUse
                        }
                        val unknownUse = use.keys - RESOURCE_USE_FIELDS
                        if (unknownUse.isNotEmpty()) {
                            promptProblem(
                                "unknown-field",
                                "Prompt \"$promptKey\" Resource use \"$resourceKey\" has unknown fields."
                            )
                        }
                        val required = use["required"]?.asBooleanOrNull() ?: true
                        if (use["required"] != null && use["required"]?.asBooleanOrNull() == null) {
                            promptProblem(
                                "invalid-field",
                                "Prompt \"$promptKey\" Resource use \"$resourceKey\" has a non-boolean required."
                            )
                        }
                        if (!resourceByKey.containsKey(resourceKey)) {
                            promptProblem(
                                "resource-unresolved",
                                "Prompt \"$promptKey\" uses Resource \"$resourceKey\", which is not declared."
                            )
                        }
                        resourceUses += PlannedPromptResource(resourceKey, required)
                    }
                }
            }

            if (kind == null || !isKey(kind) || schema == null || schema < 1 ||
                response == null || promptTitle == null || descriptionMember == null ||
                !isSafeMember(descriptionMember) || !descriptionMember.endsWith(".json")
            ) {
                diagnostics += promptDiagnostics
                descriptionCanonical[promptKey] = "<invalid>"
                return@forEachIndexed
            }

            if (descriptionMember.length > MAX_MEMBER_PATH) {
                promptProblem("limit-exceeded", "A member path is longer than $MAX_MEMBER_PATH.")
            }
            val descriptionBytes = if (descriptionMember.length <= MAX_MEMBER_PATH) {
                snapshot.readMember(descriptionMember)
            } else {
                null
            }
            if (descriptionBytes == null) {
                diagnostics += promptDiagnostics
                diagnostics += PackageDiagnostic(
                    DiagnosticScope.PROMPT, promptKey, "description-missing",
                    "Prompt \"$promptKey\"'s description \"$descriptionMember\" could not be read."
                )
                descriptionCanonical[promptKey] = "<missing>"
                return@forEachIndexed
            }
            if (descriptionBytes.size > MAX_DESCRIPTION_BYTES) {
                promptProblem(
                    "limit-exceeded",
                    "Prompt \"$promptKey\"'s description is ${descriptionBytes.size} bytes; the limit is $MAX_DESCRIPTION_BYTES."
                )
                diagnostics += promptDiagnostics
                descriptionCanonical[promptKey] = "<invalid>"
                return@forEachIndexed
            }
            val description = try {
                Json.parse(descriptionBytes)
            } catch (e: JsonParseException) {
                promptProblem(
                    "description-malformed",
                    "Prompt \"$promptKey\"'s description: ${e.message}"
                )
                diagnostics += promptDiagnostics
                descriptionCanonical[promptKey] = "<invalid>"
                return@forEachIndexed
            }
            val body = description as? JsonValue.Obj
            if (body == null) {
                promptProblem(
                    "description-malformed",
                    "Prompt \"$promptKey\"'s description must be a JSON object."
                )
                diagnostics += promptDiagnostics
                descriptionCanonical[promptKey] = "<invalid>"
                return@forEachIndexed
            }
            descriptionCanonical[promptKey] = description.canonical()

            val stem = body["stem"]?.asStringOrNull()
            val options = mutableListOf<PromptOption>()
            when (response) {
                ResponseContract.SINGLE, ResponseContract.MULTIPLE -> {
                    if (stem == null || stem.isEmpty()) {
                        promptProblem(
                            "option-invalid",
                            "Prompt \"$promptKey\" needs a stem for ${response.wire}."
                        )
                    }
                    val optionValues = body["options"] as? JsonValue.Arr
                    if (optionValues == null) {
                        promptProblem(
                            "option-invalid",
                            "Prompt \"$promptKey\" needs an options array for ${response.wire}."
                        )
                    } else if (optionValues.items.size > MAX_OPTIONS) {
                        promptProblem(
                            "limit-exceeded",
                            "Prompt \"$promptKey\" has more than $MAX_OPTIONS options."
                        )
                    } else if (optionValues.items.size < 2) {
                        promptProblem(
                            "option-invalid",
                            "Prompt \"$promptKey\" needs at least two options."
                        )
                    } else {
                        val ids = mutableSetOf<String>()
                        optionValues.items.forEachIndexed { optionIndex, optionValue ->
                            val option = optionValue as? JsonValue.Obj
                            val id = option?.get("id")?.asStringOrNull()
                            val label = option?.get("label")?.asStringOrNull()
                            if (id == null || !isKey(id)) {
                                promptProblem(
                                    "option-invalid",
                                    "Prompt \"$promptKey\" option #${optionIndex + 1} has no valid id."
                                )
                                return@forEachIndexed
                            }
                            if (!ids.add(id)) {
                                promptProblem(
                                    "option-invalid",
                                    "Prompt \"$promptKey\" repeats option id \"$id\"."
                                )
                                return@forEachIndexed
                            }
                            if (label == null || label.length !in 1..MAX_OPTION_LABEL) {
                                promptProblem(
                                    "option-invalid",
                                    "Prompt \"$promptKey\" option \"$id\" needs a label of 1–$MAX_OPTION_LABEL characters."
                                )
                                return@forEachIndexed
                            }
                            options += PromptOption(id, label)
                        }
                    }
                }
                ResponseContract.FREEFORM -> {
                    val optionValues = body["options"]
                    if (optionValues is JsonValue.Arr && optionValues.items.isNotEmpty()) {
                        promptProblem(
                            "option-invalid",
                            "Prompt \"$promptKey\" declares options but is freeform."
                        )
                    }
                }
            }

            if (response != ResponseContract.SINGLE) {
                promptProblem(
                    "response-unsupported",
                    "Prompt \"$promptKey\" asks for ${response.wire}, which this build cannot submit."
                )
            } else if (!viewerSupports(kind, schema)) {
                promptProblem(
                    "unsupported-viewer",
                    "No Viewer in this build draws kind \"$kind\" schema $schema."
                )
            }

            diagnostics += promptDiagnostics
            prompts += PlannedPrompt(
                promptKey = promptKey,
                kind = kind,
                schema = schema,
                response = response,
                title = promptTitle,
                descriptionMember = descriptionMember,
                payload = description.canonical(),
                stem = stem,
                options = options,
                assets = assets,
                resources = resourceUses,
                availability = unavailable?.let(PromptAvailability::Unavailable)
                    ?: PromptAvailability.Available
            )
        }

        if (diagnostics.any { it.scope == DiagnosticScope.PACKAGE }) return rejected()

        val canonicalManifest = manifest.canonical()
        val fingerprint = fingerprint(canonicalManifest, descriptionCanonical)

        return PackagePlan(
            rootRelativePath = snapshot.rootRelativePath,
            manifest = PackageManifest(
                protocol = PROTOCOL,
                packageKey = packageKey!!,
                revision = revision!!,
                title = title!!
            ),
            canonicalManifest = canonicalManifest,
            fingerprint = fingerprint,
            prompts = prompts,
            resources = resources,
            diagnostics = diagnostics
        )
    }

    /** The built-in Viewer set as of v0.8.2, asked of the one capability table. */
    fun supportsKind(kind: String, schema: Int): Boolean = Viewers.supports(kind, schema)

    fun isKey(value: String): Boolean = KEY.matches(value)

    /**
     * A member reference is relative, uses `/`, and cannot climb. Symlink and grant escape are the
     * host's checks; this rejects everything that is unsafe by spelling alone.
     */
    fun isSafeMember(value: String): Boolean {
        if (value.length !in 1..MAX_MEMBER_PATH) return false
        if (value.startsWith('/') || '\\' in value || ':' in value) return false
        if ('\u0000' in value) return false
        return value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    fun fingerprint(canonicalManifest: String, canonicalDescriptions: Map<String, String>): String {
        val builder = StringBuilder(canonicalManifest).append('\n')
        for (key in canonicalDescriptions.keys.sorted()) {
            builder.append(key).append('\n').append(canonicalDescriptions[key]).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(builder.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

}

enum class ResponseContract(val wire: String) {
    SINGLE("choice.single"),
    MULTIPLE("choice.multiple"),
    FREEFORM("freeform");

    companion object {
        fun fromWire(value: String): ResponseContract? = entries.firstOrNull { it.wire == value }
    }
}

data class PromptOption(val id: String, val label: String)

sealed interface PromptAvailability {
    data object Available : PromptAvailability
    data class Unavailable(val code: String) : PromptAvailability
}

data class PlannedPromptAsset(
    val member: String,
    val name: String,
    val present: Boolean
)

data class PlannedPromptResource(
    val resourceKey: String,
    val required: Boolean
)

data class PlannedPrompt(
    val promptKey: String,
    val kind: String,
    val schema: Int,
    val response: ResponseContract,
    val title: String,
    val descriptionMember: String,
    val payload: String,
    val stem: String?,
    val options: List<PromptOption>,
    val assets: List<PlannedPromptAsset>,
    val resources: List<PlannedPromptResource>,
    val availability: PromptAvailability
)

data class PlannedPackageResource(
    val resourceKey: String,
    val kind: String,
    val member: String,
    val present: Boolean,

    /**
     * The scanned member's own URI, when the host found it.
     *
     * The protocol is pure and never asks for one; the host fills it after planning by looking the
     * member up in the snapshot it already holds. Storing the document URI directly is what lets a
     * Viewer open a Resource without walking the granted tree again — the member path is kept
     * beside it for provenance and for a human reading the row.
     */
    val uri: String? = null
)

data class PackageManifest(
    val protocol: String,
    val packageKey: String,
    val revision: Int,
    val title: String
)

data class PackagePlan(
    val rootRelativePath: String,
    val manifest: PackageManifest?,
    val canonicalManifest: String?,
    val fingerprint: String?,
    val prompts: List<PlannedPrompt>,
    val resources: List<PlannedPackageResource>,
    val diagnostics: List<PackageDiagnostic>
) {
    val accepted: Boolean get() = manifest != null
}

data class PackageDiagnostic(
    val scope: DiagnosticScope,
    val key: String?,
    val code: String,
    val message: String
)

enum class DiagnosticScope { PACKAGE, PROMPT, RESOURCE, MEMBER }

/**
 * What the host already knows about one Package directory before planning.
 *
 * [presentMembers] is every file path under the Package root, relative and `/`-separated. It is a
 * set, not contents: the planner only needs to know whether a declared member exists. [readMember]
 * is called for description members only, and may return null for an unreadable file, which is a
 * Prompt diagnostic rather than a crash.
 */
class PackageSnapshot(
    val rootRelativePath: String,
    val manifestBytes: ByteArray?,
    val presentMembers: Set<String>,
    val readMember: (String) -> ByteArray? = { null }
)
