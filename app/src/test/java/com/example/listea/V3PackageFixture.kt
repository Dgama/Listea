package com.example.listea

import com.example.listea.data.ScannedFile
import java.io.File

/**
 * A small `listea.package/3` on disk, for the planning tests that need real members.
 *
 * [planPackages] reads the manifest and the Prompt bodies through the `Context`, so a plan test
 * cannot hand it strings: it writes them under a [TemporaryFolder] and speaks in the same members a
 * scan would have seen. The bodies are the built-in Viewers' own grammars, so a Prompt that parses
 * here is one the app can draw.
 */
internal class V3PackageFixture(private val root: File) {

    private val files = mutableListOf<ScannedFile>()

    fun member(path: String, text: String): ScannedFile {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return ScannedFile(file.name, path, file.toURI().toString()).also { files += it }
    }

    fun binary(path: String): ScannedFile {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D))
        return ScannedFile(file.name, path, file.toURI().toString()).also { files += it }
    }

    /** The scan a folder would produce: the manifest, the bodies and every declared member. */
    fun snapshot(manifest: String): FolderSnapshot {
        member(MANIFEST, manifest)
        return FolderSnapshot(
            files = files.toList(),
            directories = files.mapNotNull { it.relativePath.substringBeforeLast('/', "") }
                .filter { it.isNotEmpty() }
                .distinct()
        )
    }

    private companion object {
        const val MANIFEST = ".listea/manifest.json"
    }
}

/** One `prompts[]` entry. Every field the protocol accepts is written; the defaults are the common ones. */
internal fun v3Prompt(
    key: String,
    kind: String = "form",
    response: String = "structured",
    section: String? = null,
    whenCondition: String? = null,
    files: List<String> = emptyList()
): String {
    val fileJson = files.joinToString(",", "[", "]") { path ->
        val name = path.substringAfterLast('/')
        """{"member":"$path","name":"$name"}"""
    }
    val extras = buildList {
        if (section != null) add(""","section": "$section"""")
        if (whenCondition != null) add(""","when": $whenCondition""")
    }.joinToString("")
    return """
        {
          "promptKey": "$key",
          "kind": "$kind",
          "schema": 1,
          "response": "$response",
          "title": "Prompt $key",
          "description": "prompts/$key.json",
          "files": $fileJson$extras
        }
    """.trimIndent()
}

/**
 * A v3 manifest over [prompts].
 *
 * Sections are declared in [sections] as `key:title,key:title`, because a Section's title is the
 * Task's display name and the tests read it back.
 */
internal fun v3Manifest(
    prompts: List<String>,
    packageKey: String = "meals",
    sections: List<Pair<String, String>> = emptyList()
): String {
    val sectionJson = sections.joinToString(",", "[", "]") { (key, title) ->
        """{"sectionKey":"$key","title":"$title","order":0}"""
    }
    val sectionClause = if (sections.isEmpty()) "" else ""","sections": $sectionJson"""
    return """
        {
          "protocol": "listea.package/3",
          "packageKey": "$packageKey",
          "revision": 1,
          "title": "Meals",
          "prompts": [${prompts.joinToString(",")}],
          "resources": []$sectionClause
        }
    """.trimIndent()
}

/** The gate body: one required `choice.single` field whose key is `ate`. */
internal const val GATE_BODY = """
    {
      "title": "Did you eat?",
      "fields": [
        {"key": "ate", "type": "choice.single", "label": "Did you eat?",
         "options": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}]}
      ]
    }
"""

/** A media body, whose only fact is its stem. */
internal fun mediaBody(stem: String): String = """{"stem": "$stem"}"""

/** A profile body with two options. */
internal fun profileBody(stem: String): String = """
    {
      "stem": "$stem",
      "profile": {"name": "$stem"},
      "options": [{"id": "like", "label": "Like"}, {"id": "pass", "label": "Pass"}]
    }
"""

/** A vocabulary body with two options. */
internal fun vocabularyBody(word: String): String = """
    {
      "stem": "What does $word mean?",
      "word": "$word",
      "options": [{"id": "a", "label": "A"}, {"id": "b", "label": "B"}]
    }
"""

/** The condition the acceptance Package uses: the gate's `ate` answered `yes`. */
internal const val GATE_IS_YES = """{"prompt":"gate","field":"ate","equals":"yes"}"""
