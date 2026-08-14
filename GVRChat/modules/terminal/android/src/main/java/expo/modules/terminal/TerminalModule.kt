package expo.modules.terminal

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import android.util.Log
import java.io.*
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * TerminalModule — proot + Alpine Linux on-device terminal
 *
 * Flow on first run:
 *   1. Downloads proot binary (aarch64, ~1.5 MB) from GitHub
 *   2. Downloads Alpine Linux minirootfs (~5 MB compressed)
 *   3. Extracts Alpine into app's private files dir
 *   4. Every run: proot --rootfs=alpine/ /bin/sh -c "<command>"
 *
 * No root required. SELinux compatible on most devices (proot uses
 * PTRACE_ME to intercept syscalls — no kernel module needed).
 */
class TerminalModule : Module() {

    companion object {
        private const val TAG = "TerminalModule"
        private const val PROOT_URL =
            "https://github.com/termux/proot/releases/download/v5.3.0/proot-aarch64"
        private const val ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/" +
            "alpine-minirootfs-3.21.0-aarch64.tar.gz"
        private const val SETUP_DONE_FLAG = "alpine_setup_done_v1"
    }

    private val filesDir get() =
        appContext.reactContext!!.filesDir

    private val prootBin  get() = File(filesDir, "proot")
    private val alpineDir get() = File(filesDir, "alpine")
    private val setupFlag get() = File(filesDir, SETUP_DONE_FLAG)

    override fun definition() = ModuleDefinition {
        Name("Terminal")

        // Check whether Alpine has been set up
        AsyncFunction("isSetupDone") { promise: Promise ->
            promise.resolve(setupFlag.exists())
        }

        // Download + extract Alpine (call once, ~10-30 seconds)
        AsyncFunction("setup") { promise: Promise ->
            appContext.backgroundCoroutineScope.launch {
                try {
                    promise.resolve(doSetup())
                } catch (e: Exception) {
                    Log.e(TAG, "setup failed", e)
                    promise.reject("SETUP_FAILED", e.message ?: "Unknown error", e)
                }
            }
        }

        // Run a shell command inside Alpine chroot
        AsyncFunction("run") { command: String, timeoutSeconds: Int, promise: Promise ->
            appContext.backgroundCoroutineScope.launch {
                try {
                    promise.resolve(runInAlpine(command, timeoutSeconds))
                } catch (e: Exception) {
                    Log.e(TAG, "run failed: $command", e)
                    promise.reject("RUN_FAILED", e.message ?: "Unknown error", e)
                }
            }
        }

        // Write a file inside Alpine (useful for passing scripts)
        AsyncFunction("writeFile") { path: String, content: String, promise: Promise ->
            try {
                val target = File(alpineDir, path.trimStart('/'))
                target.parentFile?.mkdirs()
                target.writeText(content)
                promise.resolve("OK")
            } catch (e: Exception) {
                promise.reject("WRITE_FAILED", e.message ?: "Unknown", e)
            }
        }

        // Read a file from Alpine
        AsyncFunction("readFile") { path: String, promise: Promise ->
            try {
                val target = File(alpineDir, path.trimStart('/'))
                promise.resolve(if (target.exists()) target.readText() else "FILE_NOT_FOUND")
            } catch (e: Exception) {
                promise.reject("READ_FAILED", e.message ?: "Unknown", e)
            }
        }

        // Install an Alpine package (apk add)
        AsyncFunction("installPackage") { pkg: String, promise: Promise ->
            appContext.backgroundCoroutineScope.launch {
                try {
                    val result = runInAlpine("apk add --no-cache $pkg 2>&1", 120)
                    promise.resolve(result)
                } catch (e: Exception) {
                    promise.reject("INSTALL_FAILED", e.message ?: "Unknown", e)
                }
            }
        }
    }

    // ── SETUP ────────────────────────────────────────────────────────────────

    private fun doSetup(): String {
        val log = StringBuilder()

        // 1. Download proot
        if (!prootBin.exists()) {
            log.appendLine("Downloading proot...")
            downloadFile(PROOT_URL, prootBin)
            prootBin.setExecutable(true, false)
            log.appendLine("proot OK (${prootBin.length() / 1024} KB)")
        } else {
            log.appendLine("proot already present")
        }

        // 2. Download + extract Alpine
        if (!setupFlag.exists()) {
            alpineDir.mkdirs()
            val tarGz = File(filesDir, "alpine.tar.gz")
            log.appendLine("Downloading Alpine Linux (~5 MB)...")
            downloadFile(ALPINE_URL, tarGz)
            log.appendLine("Extracting Alpine...")
            extractTarGz(tarGz, alpineDir)
            tarGz.delete()

            // Basic Alpine setup: resolv.conf so DNS works
            File(alpineDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")

            setupFlag.writeText("done")
            log.appendLine("Alpine ready at ${alpineDir.absolutePath}")
        } else {
            log.appendLine("Alpine already extracted")
        }

        return log.toString()
    }

    // ── DOWNLOAD ─────────────────────────────────────────────────────────────

    private fun downloadFile(urlStr: String, dest: File) {
        val url = URL(urlStr)
        val conn = url.openConnection()
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.getInputStream().use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, 32 * 1024)
            }
        }
    }

    // ── TAR.GZ EXTRACTION ────────────────────────────────────────────────────

    private fun extractTarGz(tarGz: File, destDir: File) {
        // Use the system `tar` binary which is always available on Android
        val result = ProcessBuilder(
            "tar", "xzf", tarGz.absolutePath,
            "-C", destDir.absolutePath,
            "--no-same-owner"
        )
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        val exit = result.waitFor()
        if (exit != 0) throw IOException("tar failed (exit $exit): $output")
    }

    // ── RUN IN ALPINE ────────────────────────────────────────────────────────

    private fun runInAlpine(command: String, timeoutSeconds: Int): String {
        if (!setupFlag.exists()) return "ERROR: Run setup() first"
        if (!prootBin.exists()) return "ERROR: proot binary missing"

        /*
         * proot flags:
         *   --rootfs         : the Alpine directory
         *   -b /dev          : bind /dev from host (needed for /dev/urandom etc.)
         *   -b /proc         : bind /proc from host
         *   -b /sys          : bind /sys
         *   -w /root         : working directory inside chroot
         *   /bin/sh -c ...   : the command to run
         */
        val pb = ProcessBuilder(
            prootBin.absolutePath,
            "--rootfs=${alpineDir.absolutePath}",
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "${filesDir.absolutePath}:/host",   // share host files
            "-w", "/root",
            "/bin/sh", "-c",
            """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export HOME=/root
            export TERM=xterm-256color
            $command
            """.trimIndent()
        )
        pb.environment()["PROOT_NO_SECCOMP"] = "1"
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val output = StringBuilder()

        // Read output in a thread so we can apply timeout
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    output.appendLine(line)
                }
            } catch (_: IOException) {}
        }
        reader.start()

        val finished = proc.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            reader.interrupt()
            return output.toString() + "\n[TIMEOUT after ${timeoutSeconds}s]"
        }
        reader.join(2000)
        return output.toString().ifEmpty { "(no output)" }
    }
}
