package com.jinloes.prpilot.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class ProcessUtilJvmTest : FunSpec({

    lateinit var tempDir: File
    beforeTest { tempDir = Files.createTempDirectory("put-test-").toFile() }
    afterTest { tempDir.deleteRecursively() }

    context("findBinary") {

        test("first existing candidate is returned") {
            val bin = File(tempDir, "mybinary").also { it.createNewFile() }
            ProcessUtil.findBinary("fallback", listOf(bin.absolutePath)) shouldBe bin.absolutePath
        }

        test("skips non-existent paths and returns first existing") {
            val bin = File(tempDir, "real").also { it.createNewFile() }
            ProcessUtil.findBinary(
                "fallback",
                listOf("/no/such/path", bin.absolutePath, "/also/missing"),
            ) shouldBe bin.absolutePath
        }

        test("directory is not considered a file — skipped") {
            ProcessUtil.findBinary("fallback", listOf(tempDir.absolutePath)) shouldBe "fallback"
        }
    }

    context("isBinaryAvailable") {

        test("true when a candidate path exists") {
            val bin = File(tempDir, "claude").also { it.createNewFile() }
            ProcessUtil.isBinaryAvailable("claude", listOf(bin.absolutePath)) shouldBe true
        }

        test("false when no candidate exists and the name is not on PATH") {
            ProcessUtil.isBinaryAvailable(
                "pr-pilot-nonexistent-binary-xyz",
                listOf("/no/such/path", "/also/missing"),
            ) shouldBe false
        }

        test("directory candidate is not treated as an available binary") {
            ProcessUtil.isBinaryAvailable(
                "pr-pilot-nonexistent-binary-xyz",
                listOf(tempDir.absolutePath),
            ) shouldBe false
        }
    }
})
