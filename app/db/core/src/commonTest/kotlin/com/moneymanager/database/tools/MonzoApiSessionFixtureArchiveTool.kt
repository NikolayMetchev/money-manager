package com.moneymanager.database.tools

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

private const val MAGIC = "MMFX1"
private const val SALT_LEN = 16
private const val IV_LEN = 12
private const val GCM_TAG_BITS = 128
private const val PBKDF2_ITERS = 120_000

fun main(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: encrypt <inputDir> <outputFile> | decrypt <inputFile> <outputDir>")
        exitProcess(1)
    }
    when (args[0]) {
        "encrypt" -> {
            val passphrase = System.getenv("MONZO_FIXTURE_PASSPHRASE") ?: error("MONZO_FIXTURE_PASSPHRASE is required")
            encryptDirectory(File(args[1]), File(args[2]), passphrase)
        }
        "decrypt" -> {
            val passphrase = System.getenv("MONZO_FIXTURE_PASSPHRASE")
            if (passphrase.isNullOrBlank()) {
                return
            }
            decryptArchive(File(args[1]), File(args[2]), passphrase)
        }
        else -> error("Unknown command: ${args[0]}")
    }
}

fun encryptDirectory(
    inputDir: File,
    outputFile: File,
    passphrase: String,
) {
    val zipBytes = zipDirectory(inputDir)
    val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
    val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
    val key = deriveKey(passphrase, salt)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    outputFile.parentFile?.mkdirs()
    FileOutputStream(outputFile).use { fos ->
        fos.write(MAGIC.toByteArray(StandardCharsets.UTF_8))
        fos.write(salt)
        fos.write(iv)
        CipherOutputStream(fos, cipher).use { cos ->
            cos.write(zipBytes)
        }
    }
}

fun decryptArchive(
    inputFile: File,
    outputDir: File,
    passphrase: String,
) {
    if (outputDir.exists() && outputDir.listFiles()?.isNotEmpty() == true) {
        return
    }
    outputDir.mkdirs()
    FileInputStream(inputFile).use { fis ->
        val magic = fis.readNBytes(MAGIC.length).toString(Charsets.UTF_8)
        require(magic == MAGIC) { "Bad archive magic" }
        val salt = fis.readNBytes(SALT_LEN)
        val iv = fis.readNBytes(IV_LEN)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        CipherInputStream(fis, cipher).use { cis ->
            unzipDirectory(cis.readBytes(), outputDir)
        }
    }
}

private fun zipDirectory(inputDir: File): ByteArray {
    val baos = java.io.ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        Files.walk(inputDir.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val rel =
                    inputDir
                        .toPath()
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                zos.putNextEntry(ZipEntry(rel))
                Files.newInputStream(path).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
    return baos.toByteArray()
}

private fun unzipDirectory(
    zipBytes: ByteArray,
    outputDir: File,
) {
    ZipInputStream(zipBytes.inputStream()).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            val out = File(outputDir, entry.name)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fos -> zis.copyTo(fos) }
            zis.closeEntry()
        }
    }
}

private fun deriveKey(
    passphrase: String,
    salt: ByteArray,
): SecretKey {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERS, 256)
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
}
