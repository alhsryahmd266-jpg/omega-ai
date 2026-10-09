package expo.modules.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * TerminalModule — a REAL embedded Linux shell bundled inside this app's
 * own APK. No Termux app on the device, no server, no runtime download of
 * proot required — proot and termux-exec are built via the OFFICIAL
 * termux-packages Docker builder (ghcr.io/termux/package-builder, the
 * exact toolchain Termux itself uses) and shipped as jniLibs at build
 * time, verified present in this repo's native_binaries/ before packaging.
 *
 * Why NOT a runtime download of proot from termux/proot releases: that
 * repository has ZERO release assets (verified via GitHub API) — a
 * runtime download from there would 404 on every single install. This is
 * why proot is built from source once here and committed, not fetched
 * live from the user's device.
 *
 * How execution is legally possible on Android 10+ (W^X):
 *  1. `proot`, the termux-exec linker helper, and the 3 shared libraries
 *     bash needs (libandroid-support, libreadline, libiconv — verified by
 *     inspecting bash's actual dynamic symbol table) are packaged as
 *     jniLibs. Android's installer extracts these into nativeLibraryDir
 *     with execute permission automatically, at INSTALL time — exempt
 *     from the W^X block that applies to the app's writable directory.
 *  2. `bash` itself ships as a plain asset (data) and is copied to
 *     filesDir on first run. Copying data is not "execution" — unaffected
 *     by W^X.
 *  3. When proot (already running from the allowed nativeLibraryDir) execs
 *     bash (sitting in filesDir, which WOULD normally be blocked),
 *     LD_PRELOAD=libtermuxexecpreload.so on proot's environment (inherited
 *     by children) intercepts that execve() and redirects it through the
 *     trusted system linker (/system/bin/linker64) instead of a raw
 *     execve() of a writable-directory file. This is termux-exec's actual
 *     documented mechanism, not a novel trick.
 *
 * Honest, stated risk: this exact chain is known (from Termux's own open
 * GitHub issues) to behave differently across Android versions, OEMs, and
 * SELinux policies — even for Termux's own official package name. This
 * cannot be verified from a build log; it has to be tested on the actual
 * physical device.
 *
 * Honest, stated scope limits (v1):
 *  - No package manager (apt/pkg) bundled — bash + coreutils from the base
 *    bootstrap only. `installPackage()` is provided for API completeness
 *    but currently returns a clear "not supported" error rather than
 *    silently pretending to succeed.
 *  - No python3 in the base bootstrap. `runPython()` checks for a python3
 *    binary inside the rootfs and returns a clear error if absent, rather
 *    than a fake success.
 */
class TerminalModule : Module() {

  private val context: Context
    get() = appContext.reactContext ?: throw IllegalStateException("No context available")

  private fun rootfsDir(): File = File(context.filesDir, "rootfs")
  private fun binDir(): File = File(rootfsDir(), "bin")
  private fun libDir(): File = File(rootfsDir(), "lib")
  private fun bashPath(): File = File(binDir(), "bash")

  private fun nativeLibDir(): String = context.applicationInfo.nativeLibraryDir

  private fun copyAsset(name: String, dest: File) {
    dest.parentFile?.mkdirs()
    context.assets.open(name).use { input: InputStream ->
      FileOutputStream(dest).use { output -> input.copyTo(output) }
    }
  }

  /** Extracts bash + its shared libs into filesDir on first run. Pure data copy — not execution. */
  private fun ensureRootfsExtracted(): File {
    val bash = bashPath()
    if (bash.exists() && bash.length() > 0) return bash

    binDir().mkdirs()
    libDir().mkdirs()

    copyAsset("bash", bash)
    bash.setExecutable(true, false)
    bash.setReadable(true, false)

    for (lib in listOf("libandroid-support.so", "libiconv.so", "libreadline.so.8")) {
      val dest = File(libDir(), lib)
      copyAsset("rootfs-libs/$lib", dest)
      dest.setReadable(true, false)
    }

    return bash
  }

  private data class ExecOutcome(
    val stdout: String, val stderr: String, val exitCode: Int
  )

  /**
   * Starts the process and reads stdout/stderr on separate threads WHILE it runs.
   * (Reading only after waitFor() deadlocks as soon as output exceeds the pipe
   * buffer, ~64 KB.) Output is capped so a runaway command can't exhaust memory.
   */
  /**
   * Runs `pb`, protected by a foreground-service notification — but only if it's
   * actually still running after 1.5s. Fast commands (ls, pwd, cat ...) never show
   * anything; a slow one (apk add, pip install, a big git clone) gets protected
   * from being killed while the screen is off or the app is in the background.
   */
  private fun jobLabelFor(cmd: String): String {
    val oneLine = cmd.trim().replace(Regex("\\s+"), " ")
    return if (oneLine.length > 60) oneLine.take(60) + "…" else oneLine.ifEmpty { "تنفيذ أمر" }
  }

  private fun runProcess(pb: ProcessBuilder, timeoutMs: Long, jobLabel: String? = null): ExecOutcome {
    val maxChars = 200000
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    val showJob = Runnable { GvrJobService.start(context, jobLabel ?: "تنفيذ أمر في الترمنال") }
    mainHandler.postDelayed(showJob, 1500)

    try {
      val process = pb.start()
      try { process.outputStream.close() } catch (e: Exception) { }

      val outBuf = StringBuffer()
      val errBuf = StringBuffer()

      val tOut = Thread {
        try {
          val reader = process.inputStream.bufferedReader()
          val buf = CharArray(4096)
          while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            if (outBuf.length < maxChars) { outBuf.append(buf, 0, n) }
          }
        } catch (e: Exception) { }
      }
      val tErr = Thread {
        try {
          val reader = process.errorStream.bufferedReader()
          val buf = CharArray(4096)
          while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            if (errBuf.length < maxChars) { errBuf.append(buf, 0, n) }
          }
        } catch (e: Exception) { }
      }
      tOut.start()
      tErr.start()

      val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      if (!finished) {
        process.destroyForcibly()
        tOut.join(1000)
        tErr.join(1000)
        return ExecOutcome(outBuf.toString(), errBuf.toString() + "\nCommand timed out after ${timeoutMs}ms", -1)
      }
      tOut.join(2000)
      tErr.join(2000)
      return ExecOutcome(outBuf.toString(), errBuf.toString(), process.exitValue())
    } finally {
      mainHandler.removeCallbacks(showJob)
      GvrJobService.stop(context)
    }
  }

  /** Core: runs `bashArgs` through proot with termux-exec's LD_PRELOAD hook active. */
  private fun execViaProot(bashArgs: List<String>, timeoutMs: Long, extraBinds: List<String> = emptyList()): ExecOutcome {
    val bash = ensureRootfsExtracted()
    val nlib = nativeLibDir()
    val prootBin = "$nlib/libproot.so"
    val preloadLib = "$nlib/libtermuxexecpreload.so"

    if (!File(prootBin).exists()) {
      return ExecOutcome("", "libproot.so not found in $nlib — packaging error", -1)
    }

    val rootfs = rootfsDir()
    val cmd = mutableListOf(
      prootBin, "-0",
      "-b", "/proc",
      "-b", "${rootfs.absolutePath}:/"
    )
    for (bind in extraBinds) { cmd.add("-b"); cmd.add(bind) }
    cmd.add("-w"); cmd.add("/")
    cmd.add(bash.absolutePath)
    cmd.addAll(bashArgs)

    val pb = ProcessBuilder(cmd)
    val env = pb.environment()
    env["LD_PRELOAD"] = preloadLib
    env["LD_LIBRARY_PATH"] = "/lib"
    env["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
    env["HOME"] = "/"
    env["PATH"] = "/bin:/usr/bin:/system/bin"
    env["TERM"] = "xterm-256color"

    return runProcess(pb, timeoutMs, jobLabelFor(bashArgs.lastOrNull() ?: "proot"))
  }


  // ───────────────────────── Alpine Linux (proot) ─────────────────────────

  private fun alpineRoot(): File = File(context.filesDir, "alpine")

  private fun alpineInstalled(): Boolean {
    val root = alpineRoot()
    return File(root, "bin/busybox").exists() && File(root, "etc/alpine-release").exists()
  }

  private fun tarStr(b: ByteArray, off: Int, len: Int): String {
    var end = off
    val max = off + len
    while (end < max && b[end].toInt() != 0) end++
    return String(b, off, end - off, Charsets.UTF_8)
  }

  private fun tarOctal(b: ByteArray, off: Int, len: Int): Long {
    if ((b[off].toInt() and 0x80) != 0) {
      var v = 0L
      for (i in 1 until len) { v = (v shl 8) or (b[off + i].toLong() and 0xFF) }
      return v
    }
    val s = tarStr(b, off, len).trim()
    if (s.isEmpty()) return 0L
    return try { s.toLong(8) } catch (e: Exception) { 0L }
  }

  private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
    var off = 0
    while (off < len) {
      val n = input.read(buf, off, len - off)
      if (n < 0) return false
      off += n
    }
    return true
  }

  private fun skipFully(input: InputStream, count: Long) {
    var left = count
    val tmp = ByteArray(8192)
    while (left > 0) {
      val n = input.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
      if (n < 0) break
      left -= n
    }
  }

  private fun readTarString(input: InputStream, size: Long): String {
    val data = ByteArray(size.toInt())
    if (!readFully(input, data, data.size)) throw IOException("Truncated tar header data")
    skipFully(input, (512 - (size % 512)) % 512)
    return String(data, Charsets.UTF_8).trimEnd('\u0000', '\n')
  }

  private fun cleanEntryName(raw: String): String? {
    var n = raw.replace('\\', '/')
    while (n.startsWith("./")) n = n.substring(2)
    n = n.trimStart('/')
    if (n.isEmpty() || n == ".") return null
    for (part in n.split('/')) { if (part == "..") return null }
    return n
  }

  /**
   * Minimal, safe tar.gz extractor (regular files, dirs, symlinks, hardlinks,
   * GNU long names, PAX paths). Rejects any entry containing "..".
   */
  private fun extractTarGzTo(archive: File, dest: File): Int {
    var count = 0
    dest.mkdirs()
    GZIPInputStream(BufferedInputStream(FileInputStream(archive), 65536)).use { gz ->
      val header = ByteArray(512)
      var longName: String? = null
      var longLink: String? = null
      var paxPath: String? = null
      var paxLink: String? = null
      while (true) {
        if (!readFully(gz, header, 512)) break
        var allZero = true
        for (x in header) { if (x.toInt() != 0) { allZero = false; break } }
        if (allZero) break

        var name = tarStr(header, 0, 100)
        val mode = tarOctal(header, 100, 8).toInt()
        val size = tarOctal(header, 124, 12)
        val type = header[156].toInt().toChar()
        var linkName = tarStr(header, 157, 100)
        if (tarStr(header, 257, 6).startsWith("ustar")) {
          val prefix = tarStr(header, 345, 155)
          if (prefix.isNotEmpty()) name = prefix + "/" + name
        }

        if (type == 'L') { longName = readTarString(gz, size); continue }
        if (type == 'K') { longLink = readTarString(gz, size); continue }
        if (type == 'x') {
          val text = readTarString(gz, size)
          for (line in text.split('\n')) {
            val sp = line.indexOf(' ')
            if (sp < 0) continue
            val kv = line.substring(sp + 1)
            val eq = kv.indexOf('=')
            if (eq < 0) continue
            val k = kv.substring(0, eq)
            val v = kv.substring(eq + 1)
            if (k == "path") paxPath = v
            if (k == "linkpath") paxLink = v
          }
          continue
        }
        if (type == 'g') { skipFully(gz, size + (512 - (size % 512)) % 512); continue }

        val entryName = cleanEntryName(paxPath ?: longName ?: name)
        if (paxLink != null) linkName = paxLink!! else if (longLink != null) linkName = longLink!!
        longName = null; longLink = null; paxPath = null; paxLink = null

        val padded = size + (512 - (size % 512)) % 512
        if (entryName == null) { skipFully(gz, padded); continue }
        val target = File(dest, entryName)

        when (type) {
          '5' -> {
            target.mkdirs()
            try { Os.chmod(target.absolutePath, (mode and 4095) or 448) } catch (e: Exception) { }
            count++
          }
          '2' -> {
            target.parentFile?.mkdirs()
            if (target.exists() || target.isSymbolicLink()) target.delete()
            try { Os.symlink(linkName, target.absolutePath) } catch (e: Exception) { }
            count++
          }
          '1' -> {
            target.parentFile?.mkdirs()
            val src = cleanEntryName(linkName)?.let { File(dest, it) }
            if (target.exists()) target.delete()
            if (src != null && src.exists()) {
              try { Os.link(src.absolutePath, target.absolutePath) } catch (e: Exception) {
                try { src.copyTo(target, overwrite = true) } catch (e2: Exception) { }
              }
            }
            count++
          }
          '0', '\u0000', '7' -> {
            target.parentFile?.mkdirs()
            if (target.exists() || target.isSymbolicLink()) target.delete()
            FileOutputStream(target).use { out ->
              var left = size
              val tmp = ByteArray(65536)
              while (left > 0) {
                val n = gz.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
                if (n < 0) throw IOException("Unexpected end of archive while reading " + target.name)
                out.write(tmp, 0, n)
                left -= n
              }
            }
            skipFully(gz, (512 - (size % 512)) % 512)
            try { Os.chmod(target.absolutePath, (mode and 4095) or 384) } catch (e: Exception) { }
            count++
          }
          else -> { skipFully(gz, padded) }
        }
      }
    }
    return count
  }

  private fun File.isSymbolicLink(): Boolean {
    return try {
      val st = Os.lstat(this.absolutePath)
      (st.st_mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    } catch (e: Exception) { false }
  }

  private fun alpineProotCommand(command: String): List<String> {
    val nlib = nativeLibDir()
    val cmd = mutableListOf(
      // --sysvipc: most Android kernels (esp. Qualcomm) ship without CONFIG_SYSVIPC.
      // apk-tools v3 locks its database with a SysV semaphore on every install/upgrade,
      // so without this flag "apk add" segfaults (exit code 139) the instant it tries
      // to write. This emulates SysV IPC in userspace, exactly like Termux's proot-distro
      // does for Alpine. Confirmed against the user's own on-device crash log.
      "$nlib/libproot.so", "--kill-on-exit", "-0", "--link2symlink", "--sysvipc",
      "-r", alpineRoot().absolutePath,
      "-b", "/dev", "-b", "/proc", "-b", "/sys"
    )
    if (File("/storage/emulated/0").exists()) {
      cmd.add("-b"); cmd.add("/storage/emulated/0:/sdcard")
    }
    cmd.addAll(listOf(
      "-w", "/root",
      "/usr/bin/env", "-i",
      "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/sh",
      "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "/bin/sh", "-c", command
    ))
    return cmd
  }

  private fun applyProotEnv(pb: ProcessBuilder) {
    val nlib = nativeLibDir()
    val tmp = File(context.cacheDir, "proot-tmp")
    tmp.mkdirs()
    val env = pb.environment()
    env["PROOT_TMP_DIR"] = tmp.absolutePath
    env["PROOT_NO_SECCOMP"] = "1"
    env["LD_LIBRARY_PATH"] = nlib
    val loader = File(nlib, "libproot-loader.so")
    if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
    env.remove("LD_PRELOAD")
  }

  private fun execAlpine(command: String, timeoutMs: Long): ExecOutcome {
    val nlib = nativeLibDir()
    if (!File(nlib, "libproot.so").exists()) {
      return ExecOutcome("", "libproot.so not found in $nlib (native libs were not extracted)", -1)
    }
    if (!alpineInstalled()) {
      return ExecOutcome("", "Alpine Linux is not installed yet. Install it from Settings first.", -1)
    }
    val pb = ProcessBuilder(alpineProotCommand(command))
    applyProotEnv(pb)
    return runProcess(pb, timeoutMs, jobLabelFor(command))
  }

  override fun definition() = ModuleDefinition {
    Name("Terminal")

    AsyncFunction("isSetupDone") { promise: expo.modules.kotlin.Promise ->
      try {
        val libDirOk = File(nativeLibDir(), "libproot.so").exists() &&
                       File(nativeLibDir(), "libtermuxexecpreload.so").exists()
        val bashOk = bashPath().exists() && bashPath().length() > 0
        promise.resolve(libDirOk && bashOk)
      } catch (e: Exception) {
        promise.resolve(false)
      }
    }

    AsyncFunction("setupTerminal") { promise: expo.modules.kotlin.Promise ->
      try {
        val bash = ensureRootfsExtracted()
        promise.resolve(mapOf(
          "bashPath" to bash.absolutePath,
          "nativeLibDir" to nativeLibDir(),
          "rootfsDir" to rootfsDir().absolutePath
        ))
      } catch (e: Exception) {
        promise.reject("SETUP_ERROR", "Failed to extract rootfs: ${e.message}", e)
      }
    }

    AsyncFunction("run") { command: String, timeoutMs: Int, promise: expo.modules.kotlin.Promise ->
      try {
        val result = execViaProot(listOf("-c", command), timeoutMs.toLong())
        promise.resolve(mapOf(
          "stdout" to result.stdout,
          "stderr" to result.stderr,
          "exitCode" to result.exitCode,
          "success" to (result.exitCode == 0)
        ))
      } catch (e: Exception) {
        promise.reject("EXEC_ERROR", "Command execution failed: ${e.message}", e)
      }
    }

    AsyncFunction("runShell") { command: String, timeoutMs: Int, promise: expo.modules.kotlin.Promise ->
      try {
        val home = File(context.filesDir, "home")
        home.mkdirs()
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(home)
        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
        env["TERM"] = "xterm-256color"
        val result = runProcess(pb, timeoutMs.toLong(), jobLabelFor(command))
        promise.resolve(mapOf(
          "stdout" to result.stdout,
          "stderr" to result.stderr,
          "exitCode" to result.exitCode,
          "success" to (result.exitCode == 0)
        ))
      } catch (e: Exception) {
        promise.reject("EXEC_ERROR", "Shell execution failed: ${e.message}", e)
      }
    }

    AsyncFunction("sha256File") { path: String, promise: expo.modules.kotlin.Promise ->
      try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(File(path)).use { input ->
          val buf = ByteArray(65536)
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
          }
        }
        val sb = StringBuilder()
        for (b in md.digest()) { sb.append(String.format("%02x", b)) }
        promise.resolve(sb.toString())
      } catch (e: Exception) {
        promise.reject("HASH_ERROR", "sha256 failed: ${e.message}", e)
      }
    }

    AsyncFunction("alpineStatus") { promise: expo.modules.kotlin.Promise ->
      try {
        val nlib = nativeLibDir()
        promise.resolve(mapOf(
          "installed" to alpineInstalled(),
          "prootPresent" to File(nlib, "libproot.so").exists(),
          "loaderPresent" to File(nlib, "libproot-loader.so").exists(),
          "tallocPresent" to File(nlib, "libtalloc.so").exists(),
          "shmemPresent" to File(nlib, "libandroid-shmem.so").exists(),
          "targetSdk" to context.applicationInfo.targetSdkVersion,
          "androidSdk" to Build.VERSION.SDK_INT,
          "rootDir" to alpineRoot().absolutePath
        ))
      } catch (e: Exception) {
        promise.reject("STATUS_ERROR", "alpineStatus failed: ${e.message}", e)
      }
    }

    AsyncFunction("extractTarGz") { archivePath: String, destDir: String, promise: expo.modules.kotlin.Promise ->
      try {
        val archive = File(archivePath)
        if (!archive.exists()) {
          promise.reject("NOT_FOUND", "Archive not found: $archivePath", null)
          return@AsyncFunction
        }
        val dest = File(destDir)
        if (dest.exists()) dest.deleteRecursively()
        val n = extractTarGzTo(archive, dest)
        promise.resolve(n)
      } catch (e: Exception) {
        promise.reject("EXTRACT_ERROR", "Extraction failed: ${e.message}", e)
      }
    }

    AsyncFunction("prepareAlpine") { promise: expo.modules.kotlin.Promise ->
      try {
        val root = alpineRoot()
        for (d in listOf("tmp", "root", "dev", "proc", "sys", "sdcard", "etc", "run", "var/tmp")) {
          File(root, d).mkdirs()
        }
        try { Os.chmod(File(root, "tmp").absolutePath, 1023) } catch (e: Exception) { }
        try { Os.chmod(File(root, "var/tmp").absolutePath, 1023) } catch (e: Exception) { }
        File(root, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n")
        val hosts = File(root, "etc/hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        promise.resolve(alpineInstalled())
      } catch (e: Exception) {
        promise.reject("PREPARE_ERROR", "prepareAlpine failed: ${e.message}", e)
      }
    }

    AsyncFunction("runAlpine") { command: String, timeoutMs: Int, promise: expo.modules.kotlin.Promise ->
      try {
        val result = execAlpine(command, timeoutMs.toLong())
        promise.resolve(mapOf(
          "stdout" to result.stdout,
          "stderr" to result.stderr,
          "exitCode" to result.exitCode,
          "success" to (result.exitCode == 0)
        ))
      } catch (e: Exception) {
        promise.reject("EXEC_ERROR", "Alpine execution failed: ${e.message}", e)
      }
    }

    AsyncFunction("selfTest") { promise: expo.modules.kotlin.Promise ->
      try {
        val sb = StringBuilder()
        val nlib = nativeLibDir()
        sb.appendLine("android_sdk=${Build.VERSION.SDK_INT}  targetSdk=${context.applicationInfo.targetSdkVersion}  abi=${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("nativeLibraryDir=$nlib")
        for (n in listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")) {
          val f = File(nlib, n)
          sb.appendLine("lib $n: " + (if (f.exists()) "present (${f.length()} bytes)" else "MISSING"))
        }
        try {
          val dir = File(context.filesDir, "exec_test")
          dir.mkdirs()
          val dst = File(dir, "toybox")
          File("/system/bin/toybox").copyTo(dst, overwrite = true)
          dst.setExecutable(true, false)
          val r = runProcess(ProcessBuilder(dst.absolutePath, "true"), 5000)
          sb.appendLine("exec from app data dir: " + (if (r.exitCode == 0) "ALLOWED" else "BLOCKED (exit ${r.exitCode}) ${r.stderr.take(160)}"))
        } catch (e: Exception) {
          sb.appendLine("exec from app data dir: BLOCKED (${e.message})")
        }
        try {
          val pb = ProcessBuilder("$nlib/libproot.so", "--version")
          applyProotEnv(pb)
          val r = runProcess(pb, 8000)
          val firstLine = r.stdout.trim().lines().firstOrNull() ?: ""
          sb.appendLine("proot --version: exit=${r.exitCode} $firstLine ${r.stderr.take(240)}")
        } catch (e: Exception) {
          sb.appendLine("proot --version: FAILED (${e.message})")
        }
        if (alpineInstalled()) {
          val r = execAlpine("cat /etc/alpine-release; uname -m; echo guest_ok", 30000)
          sb.appendLine("alpine: exit=${r.exitCode} ${r.stdout.trim().replace('\n', ' ')} ${r.stderr.take(300)}")
        } else {
          sb.appendLine("alpine: not installed")
        }
        promise.resolve(sb.toString())
      } catch (e: Exception) {
        promise.reject("SELFTEST_ERROR", "selfTest failed: ${e.message}", e)
      }
    }

    AsyncFunction("runPython") { code: String, timeoutMs: Int, promise: expo.modules.kotlin.Promise ->
      try {
        if (!alpineInstalled()) {
          promise.resolve(mapOf(
            "stdout" to "", "stderr" to "Alpine Linux is not installed. Install it from Settings, then run: apk add python3",
            "exitCode" to -1, "success" to false))
          return@AsyncFunction
        }
        val check = execAlpine("command -v python3", 15000)
        if (check.exitCode != 0 || check.stdout.isBlank()) {
          promise.resolve(mapOf(
            "stdout" to "", "stderr" to "python3 is not installed in Alpine yet. Run in the terminal: apk add python3 py3-pip",
            "exitCode" to -1, "success" to false))
          return@AsyncFunction
        }
        File(alpineRoot(), "tmp").mkdirs()
        File(alpineRoot(), "tmp/gvr_script.py").writeText(code)
        val result = execAlpine("python3 /tmp/gvr_script.py", timeoutMs.toLong())
        promise.resolve(mapOf(
          "stdout" to result.stdout, "stderr" to result.stderr,
          "exitCode" to result.exitCode, "success" to (result.exitCode == 0)))
      } catch (e: Exception) {
        promise.reject("EXEC_ERROR", "Python execution failed: ${e.message}", e)
      }
    }

    AsyncFunction("installPackage") { packageName: String, promise: expo.modules.kotlin.Promise ->
      try {
        val pkgs = packageName.trim()
        if (pkgs.isEmpty() || !Regex("^[A-Za-z0-9._+@ -]+$").matches(pkgs)) {
          promise.resolve(mapOf("stdout" to "", "stderr" to "Invalid package name(s).", "exitCode" to -1, "success" to false))
          return@AsyncFunction
        }
        val result = execAlpine("apk add --no-cache $pkgs", 600000)
        promise.resolve(mapOf(
          "stdout" to result.stdout, "stderr" to result.stderr,
          "exitCode" to result.exitCode, "success" to (result.exitCode == 0)))
      } catch (e: Exception) {
        promise.reject("EXEC_ERROR", "Package install failed: ${e.message}", e)
      }
    }

    AsyncFunction("writeFile") { path: String, content: String, promise: expo.modules.kotlin.Promise ->
      try {
        val rootfs = rootfsDir()
        val target = File(rootfs, path.removePrefix("/"))
        target.parentFile?.mkdirs()
        target.writeText(content)
        promise.resolve(true)
      } catch (e: Exception) {
        promise.reject("WRITE_ERROR", "Failed to write file: ${e.message}", e)
      }
    }

    AsyncFunction("readFile") { path: String, promise: expo.modules.kotlin.Promise ->
      try {
        val rootfs = rootfsDir()
        val target = File(rootfs, path.removePrefix("/"))
        if (!target.exists()) {
          promise.reject("NOT_FOUND", "File not found: $path", null)
          return@AsyncFunction
        }
        promise.resolve(target.readText())
      } catch (e: Exception) {
        promise.reject("READ_ERROR", "Failed to read file: ${e.message}", e)
      }
    }
  }
}
