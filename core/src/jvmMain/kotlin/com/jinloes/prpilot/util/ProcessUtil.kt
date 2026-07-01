package com.jinloes.prpilot.util

import java.io.File

actual object ProcessUtil {
    @JvmStatic
    actual fun findBinary(name: String, candidates: List<String>): String =
        candidates.firstOrNull { File(it).isFile } ?: name

    /**
     * Reports whether [name] is resolvable without spawning it: true when one of the hard-coded
     * [candidates] is an existing file, or when [name] is found on the process `PATH`. Mirrors the
     * resolution a later spawn would use, so it is a faithful preflight for provider CLIs.
     */
    @JvmStatic
    fun isBinaryAvailable(name: String, candidates: List<String>): Boolean {
        if (candidates.any { File(it).isFile }) return true
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparatorChar).any { dir ->
            dir.isNotBlank() && File(dir, name).let { it.isFile && it.canExecute() }
        }
    }
}
