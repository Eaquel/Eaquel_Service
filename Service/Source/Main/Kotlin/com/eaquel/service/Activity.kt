@file:Suppress("DEPRECATION", "OPT_IN_USAGE")
package com.eaquel.service

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private object P {
    const val CNXN = 0x4e584e43; const val AUTH  = 0x48545541
    const val OPEN = 0x4e45504f; const val OKAY  = 0x59414b4f
    const val CLSE = 0x45534c43; const val WRTE  = 0x45545257
    const val STLS = 0x534c5453; const val VER   = 0x01000001
    const val MAX  = 1024 * 256; const val STLSV = 0x01000000
    const val TOK  = 1;          const val SIG   = 2; const val PUB = 3
    const val HDR  = 24
    fun crc(d: ByteArray?) = d?.fold(0) { a, b -> a + (b.toInt() and 0xFF) } ?: 0
    fun enc(s: String)     = (s + "\u0000").toByteArray(Charsets.UTF_8)
}

private data class AdbMsg(
    val cmd: Int, val a0: Int, val a1: Int,
    val len: Int = 0, val crc: Int = 0, val magic: Int = cmd.inv(),
    val data: ByteArray? = null
) {
    fun bytes(): ByteArray {
        val b = ByteBuffer.allocate(P.HDR + len).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd); b.putInt(a0); b.putInt(a1)
        b.putInt(len); b.putInt(crc); b.putInt(magic)
        data?.let { b.put(it) }
        return b.array()
    }
    fun validate() {
        check(magic == cmd.inv()) { "Geçersiz ADB magic: cmd=${cmd.toString(16)} magic=${magic.toString(16)}" }
    }
    override fun equals(o: Any?) = o is AdbMsg && cmd == o.cmd && a0 == o.a0
    override fun hashCode()      = 31 * cmd + a0
    companion object {
        fun of(c: Int, a0: Int, a1: Int, d: ByteArray? = null): AdbMsg {
            val l = d?.size ?: 0
            return AdbMsg(c, a0, a1, l, P.crc(d), c.inv(), d)
        }
        fun of(c: Int, a0: Int, a1: Int, s: String) = of(c, a0, a1, P.enc(s))
    }
}

class GameAdbKey private constructor(
    private val ks: java.security.KeyStore,
    private val alias: String
) {
    private val rsaPub  get() = ks.getCertificate(alias).publicKey as java.security.interfaces.RSAPublicKey
    private val privKey get() = ks.getKey(alias, null) as java.security.PrivateKey
    private val cert    get() = ks.getCertificate(alias) as java.security.cert.X509Certificate

    val pubBytes: ByteArray by lazy {
        val n  = rsaPub.modulus
        val e  = rsaPub.publicExponent.toInt()
        val nW = 64
        fun encLE(v: java.math.BigInteger): ByteArray {
            val o = ByteArray(nW * 4); var c = v
            for (i in 0 until nW) {
                val w = c.and(java.math.BigInteger.valueOf(0xFFFFFFFFL)).toLong().toInt()
                o[i*4]   = (w         and 0xFF).toByte()
                o[i*4+1] = ((w shr 8)  and 0xFF).toByte()
                o[i*4+2] = ((w shr 16) and 0xFF).toByte()
                o[i*4+3] = ((w shr 24) and 0xFF).toByte()
                c = c.shiftRight(32)
            }
            return o
        }
        fun le32(v: Long) = byteArrayOf(
            (v         and 0xFF).toByte(), ((v shr 8)  and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
        val B    = java.math.BigInteger.ONE.shiftLeft(32)
        val n0inv = B.subtract(n.modInverse(B)).and(B.subtract(java.math.BigInteger.ONE)).toLong()
        val rr   = java.math.BigInteger.ONE.shiftLeft(2 * 2048).mod(n)
        val raw  = le32(nW.toLong()) + le32(n0inv) + encLE(n) + encLE(rr) + le32(e.toLong())
        (android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP) +
         " eaquel@service\n").toByteArray(Charsets.UTF_8)
    }

    fun sign(tok: ByteArray): ByteArray =
        Signature.getInstance("SHA1withRSA").run { initSign(privKey); update(tok); sign() }

    val sslContext: SSLContext by lazy {
        val myCert  = cert
        val myKey   = privKey
        val myAlias = alias
        val km = object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<out java.security.Principal>?) = arrayOf(myAlias)
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = myAlias
            override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out java.security.Principal>?, e: javax.net.ssl.SSLEngine?) = myAlias
            override fun getServerAliases(t: String?, i: Array<out java.security.Principal>?) = null
            override fun chooseServerAlias(t: String?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = null
            override fun chooseEngineServerAlias(t: String?, i: Array<out java.security.Principal>?, e: javax.net.ssl.SSLEngine?) = null
            override fun getCertificateChain(a: String?) = arrayOf(myCert)
            override fun getPrivateKey(a: String?) = myKey
        }

        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        })

        SSLContext.getInstance("TLS").apply { init(arrayOf(km), tm, SecureRandom()) }
    }

    val pairingSSLContext: SSLContext by lazy {
        buildPairingSSLContext()
    }

    companion object {
        private const val ALIAS = "eaquel_adb_v3"
        fun getOrCreate(ctx: Context): GameAdbKey {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA,
                    "AndroidKeyStore"
                ).apply {
                    initialize(
                        android.security.keystore.KeyGenParameterSpec.Builder(
                            ALIAS,
                            android.security.keystore.KeyProperties.PURPOSE_SIGN
                        ).setKeySize(2048)
                         .setSignaturePaddings(
                             android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                             android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PSS
                         )
                         .setDigests(
                             android.security.keystore.KeyProperties.DIGEST_SHA1,
                             android.security.keystore.KeyProperties.DIGEST_SHA256,
                             android.security.keystore.KeyProperties.DIGEST_SHA512
                         )
                         .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=adb"))
                         .setCertificateSerialNumber(java.math.BigInteger.ONE)
                         .build()
                    )
                }.generateKeyPair()
            }
            return GameAdbKey(ks, ALIAS)
        }
        fun reset(ctx: Context) {
            try {
                java.security.KeyStore.getInstance("AndroidKeyStore")
                    .also { it.load(null) }.deleteEntry(ALIAS)
            } catch (_: Exception) {}
        }
    }

    private fun buildPairingSSLContext(): SSLContext {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val ephemeralCert = buildMinimalSelfSignedCert(kp)
        val ks = java.security.KeyStore.getInstance("PKCS12").also { it.load(null, null) }
        ks.setKeyEntry("pairing", kp.private, null, arrayOf(ephemeralCert))
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        })
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tm, SecureRandom()) }
    }

    private fun buildMinimalSelfSignedCert(kp: java.security.KeyPair): java.security.cert.X509Certificate {
        fun lenBytes(n: Int): ByteArray = when {
            n < 0x80  -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else      -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
        }
        fun tlv(tag: Int, data: ByteArray) = byteArrayOf(tag.toByte()) + lenBytes(data.size) + data
        fun seq(data: ByteArray) = tlv(0x30, data)
        fun set(data: ByteArray) = tlv(0x31, data)
        fun oid(b: ByteArray)    = tlv(0x06, b)
        fun utf8(s: String)      = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
        fun integer(data: ByteArray): ByteArray {
            val d = if (data[0] < 0) byteArrayOf(0x00) + data else data
            return tlv(0x02, d)
        }
        fun utcTime(s: String)   = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
        fun bitString(data: ByteArray) = tlv(0x03, byteArrayOf(0x00) + data)
        fun ctx0(data: ByteArray) = tlv(0xA0, data)

        val oidSha256Rsa = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
        val oidCn        = byteArrayOf(0x55, 0x04, 0x03)

        val sigAlgId = seq(oid(oidSha256Rsa) + byteArrayOf(0x05, 0x00))
        val name     = seq(set(seq(oid(oidCn) + utf8("adb"))))
        val validity = seq(utcTime("200101000000Z") + utcTime("491231235959Z"))
        val serial   = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val version  = ctx0(tlv(0x02, byteArrayOf(0x02)))
        val spki     = kp.public.encoded

        val tbs = seq(version + integer(serial) + sigAlgId + name + validity + name + spki)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(tbs); sign()
        }
        val certDer = seq(tbs + sigAlgId + bitString(sig))

        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(certDer))
                    as java.security.cert.X509Certificate
    }
}

class AdbConnection(
    private val host: String,
    private val port: Int,
    private val key: GameAdbKey,
    private val timeoutMs: Int = 3_000
) : Closeable {
    private lateinit var sock: Socket
    private lateinit var pi: DataInputStream; private lateinit var po: DataOutputStream
    private var tls = false
    private lateinit var ts: SSLSocket
    private lateinit var ti: DataInputStream; private lateinit var to: DataOutputStream
    private val i   get() = if (tls) ti else pi
    private val o   get() = if (tls) to else po
    private val alive = AtomicBoolean(false)

    @RequiresApi(30)
    fun connect() {
        require(port in 1..65535) { "Geçersiz port: $port" }
        val inetAddr = try {
            java.net.InetAddress.getByName(host)
        } catch (_: Exception) {
            java.net.InetAddress.getByName("127.0.0.1")
        }
        sock = java.net.Socket().apply {
            tcpNoDelay = true
            soTimeout  = timeoutMs
            connect(java.net.InetSocketAddress(inetAddr, port), timeoutMs)
        }
        pi  = DataInputStream(sock.getInputStream())
        po  = DataOutputStream(sock.getOutputStream())
        send(P.CNXN, P.VER, P.MAX, "host::")
        var m = recv()
        when (m.cmd) {
            P.STLS -> {
                send(P.STLS, P.STLSV, 0)
                ts = configureSslSocket(
                    key.sslContext.socketFactory.createSocket(sock, host, port, true) as SSLSocket
                )
                ts.startHandshake()
                ti = DataInputStream(ts.inputStream)
                to = DataOutputStream(ts.outputStream)
                tls = true
                m   = recv()
            }
            P.AUTH -> {
                send(P.AUTH, P.SIG, 0, key.sign(m.data!!))
                m = recv()
                if (m.cmd != P.CNXN) {
                    send(P.AUTH, P.PUB, 0, key.pubBytes)
                    m = recv()
                }
            }
        }
        check(m.cmd == P.CNXN) { "Bağlantı hatası: ${m.cmd.toString(16)}" }
        alive.set(true)
    }

    fun shell(cmd: String, cb: ((String) -> Unit)? = null): Pair<Int, String> {
        val augmented = "($cmd); echo __EXIT__:\$?"
        send(P.OPEN, 1, 0, "shell:$augmented")
        val sb = StringBuilder()
        var m  = recv()
        if (m.cmd != P.OKAY) return -1 to "Open failed"
        while (true) {
            m = recv()
            when (m.cmd) {
                P.WRTE -> {
                    val s = m.data?.let { String(it, Charsets.UTF_8) } ?: ""
                    sb.append(s); cb?.invoke(s)
                    send(P.OKAY, 1, m.a0)
                }
                P.CLSE -> { send(P.CLSE, 1, m.a0); break }
                else   -> break
            }
        }
        val raw       = sb.toString()
        val exitRegex = Regex("""__EXIT__:(\d+)\s*$""")
        val exitMatch = exitRegex.find(raw)
        val exitCode  = exitMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val output    = if (exitMatch != null) raw.substringBefore("__EXIT__:").trimEnd() else raw.trim()
        return exitCode to output
    }

    fun shellStr(cmd: String) = shell(cmd).second.trim()
    fun isAlive()             = alive.get() && !sock.isClosed

    private fun send(c: Int, a0: Int, a1: Int, d: ByteArray? = null) {
        val m = AdbMsg.of(c, a0, a1, d); o.write(m.bytes()); o.flush()
    }
    private fun send(c: Int, a0: Int, a1: Int, s: String) = send(c, a0, a1, P.enc(s))
    private fun recv(): AdbMsg {
        val h = ByteBuffer.allocate(P.HDR).order(ByteOrder.LITTLE_ENDIAN)
        i.readFully(h.array(), 0, P.HDR)
        val c = h.int; val a0 = h.int; val a1 = h.int
        val l = h.int; val crc = h.int; val mg  = h.int
        val d = if (l > 0) ByteArray(l).also { i.readFully(it, 0, l) } else null
        return AdbMsg(c, a0, a1, l, crc, mg, d).also { it.validate() }
    }

    override fun close() {
        alive.set(false)
        listOf<() -> Unit>(
            { if (tls) { runCatching { ti.close() }; runCatching { to.close() }; runCatching { ts.close() } } },
            { runCatching { pi.close() } },
            { runCatching { po.close() } },
            { runCatching { sock.close() } }
        ).forEach { it() }
    }
}

private fun configureSslSocket(s: SSLSocket): SSLSocket {
    s.useClientMode = true

    val supported = s.supportedProtocols
    val preferred = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray()
    if (preferred.isNotEmpty()) s.enabledProtocols = preferred
    return s
}

private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length <= 32) { "Bu implementasyon ≤32 byte destekler" }

    val zeros = ByteArray(32)
    val macExt = javax.crypto.Mac.getInstance("HmacSHA256")
    macExt.init(javax.crypto.spec.SecretKeySpec(zeros, "HmacSHA256"))
    val prk = macExt.doFinal(ikm)

    val macExp = javax.crypto.Mac.getInstance("HmacSHA256")
    macExp.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
    macExp.update(info)
    macExp.update(0x01.toByte())
    return macExp.doFinal().copyOf(length)
}

private fun buildNonce(ivPrefix: ByteArray, counter: Long): ByteArray {
    val nonce = ByteArray(12)
    System.arraycopy(ivPrefix, 0, nonce, 0, 4)
    for (i in 0..7) nonce[4 + i] = ((counter shr (56 - i * 8)) and 0xFF).toByte()
    return nonce
}

private fun aesGcmEncrypt(key: ByteArray, ivPrefix: ByteArray, counter: Long, data: ByteArray): ByteArray {
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        javax.crypto.spec.SecretKeySpec(key, "AES"),
        javax.crypto.spec.GCMParameterSpec(128, buildNonce(ivPrefix, counter))
    )
    return cipher.doFinal(data)
}

private fun aesGcmDecrypt(key: ByteArray, ivPrefix: ByteArray, counter: Long, data: ByteArray): ByteArray? {
    return try {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, buildNonce(ivPrefix, counter))
        )
        cipher.doFinal(data)
    } catch (_: Exception) { null }
}

private object KotlinSpake2 {

    private val P = java.math.BigInteger.TWO.pow(255)
                     .subtract(java.math.BigInteger.valueOf(19))

    private val Q = java.math.BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")

    private val D = java.math.BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")

    private val Bx = java.math.BigInteger("15112221349535807912866137220509078750507884956996801676718808947627381228049")
    private val By = java.math.BigInteger("46316835694926478169428394003475163141307993866256225615783033011972563100100")

    private val M_BYTES = byteArrayOf(
        0xd0.toByte(),0x48,0x03,0x2c,0x6e.toByte(),0xa0.toByte(),0xb6.toByte(),0xd6.toByte(),
        0x97.toByte(),0xdd.toByte(),0xd0.toByte(),0xf0.toByte(),0x76,0x63,0x8c.toByte(),0x77,
        0x46,0xdf.toByte(),0x1a,0xfb.toByte(),0x8e.toByte(),0x2b,0x98.toByte(),0x38,
        0x4d,0xa3.toByte(),0xa9.toByte(),0x8c.toByte(),0x88.toByte(),0xab.toByte(),0x21,0x95.toByte()
    )

    private val N_BYTES = byteArrayOf(
        0x4a.toByte(),0xa5.toByte(),0xf8.toByte(),0x56.toByte(),
        0x88.toByte(),0x7c.toByte(),0x7d.toByte(),0xfd.toByte(),
        0x9b.toByte(),0xae.toByte(),0x7b.toByte(),0x1b.toByte(),
        0xc0.toByte(),0x6a.toByte(),0x84.toByte(),0x54.toByte(),
        0xf9.toByte(),0x48.toByte(),0xaf.toByte(),0xdd.toByte(),
        0xf7.toByte(),0x76.toByte(),0x7d.toByte(),0xe7.toByte(),
        0x5c.toByte(),0x19.toByte(),0x8d.toByte(),0x11.toByte(),
        0xbe.toByte(),0xb7.toByte(),0xdd.toByte(),0x26.toByte()
    )

    private data class Pt(val X: java.math.BigInteger, val Y: java.math.BigInteger,
                           val Z: java.math.BigInteger, val T: java.math.BigInteger)

    private val IDENTITY = Pt(java.math.BigInteger.ZERO, java.math.BigInteger.ONE,
                                java.math.BigInteger.ONE, java.math.BigInteger.ZERO)

    private fun fp(a: java.math.BigInteger) = a.mod(P).let { if (it.signum() < 0) it.add(P) else it }

    private fun add(A: Pt, B: Pt): Pt {
        val a  = fp(A.Y.subtract(A.X)).multiply(fp(B.Y.subtract(B.X))).mod(P)
        val b  = fp(A.Y.add(A.X)).multiply(fp(B.Y.add(B.X))).mod(P)
        val c  = A.T.multiply(B.T).mod(P).multiply(D).mod(P)
                   .multiply(java.math.BigInteger.TWO).mod(P)
        val d  = A.Z.multiply(B.Z).mod(P).multiply(java.math.BigInteger.TWO).mod(P)
        val e  = fp(b.subtract(a))
        val f  = fp(d.subtract(c))
        val g  = fp(d.add(c))
        val h  = fp(b.add(a))
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P),
                   f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(k: java.math.BigInteger, pt: Pt): Pt {
        var r = IDENTITY; var cur = pt; var n = k.mod(Q)
        while (n.signum() > 0) {
            if (n.testBit(0)) r = add(r, cur)
            cur = add(cur, cur); n = n.shiftRight(1)
        }
        return r
    }

    private fun neg(pt: Pt) = Pt(fp(P.subtract(pt.X)), pt.Y, pt.Z, fp(P.subtract(pt.T)))

    private fun decode(bytes: ByteArray): Pt? {
        if (bytes.size != 32) return null
        val signX = (bytes[31].toInt() and 0x80) != 0
        val yb = bytes.clone(); yb[31] = (yb[31].toInt() and 0x7F).toByte()
        val y = java.math.BigInteger(1, yb.reversedArray())
        if (y >= P) return null

        val y2  = y.multiply(y).mod(P)
        val num = y2.subtract(java.math.BigInteger.ONE).mod(P)
        val den = D.multiply(y2).mod(P).add(java.math.BigInteger.ONE).mod(P)
        val x2  = num.multiply(den.modPow(P.subtract(java.math.BigInteger.TWO), P)).mod(P)

        val exp = P.add(java.math.BigInteger.valueOf(3)).divide(java.math.BigInteger.valueOf(8))
        var x   = x2.modPow(exp, P)

        val sqrtM1 = java.math.BigInteger.TWO.modPow(
            P.subtract(java.math.BigInteger.ONE).divide(java.math.BigInteger.valueOf(4)), P)
        if (x.multiply(x).mod(P) != x2) {
            x = x.multiply(sqrtM1).mod(P)
        }
        if (x.multiply(x).mod(P) != x2) return null

        if (x.testBit(0) != signX) x = P.subtract(x)
        return Pt(x, y, java.math.BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun encode(pt: Pt): ByteArray {
        val zi = pt.Z.modInverse(P)
        val x  = fp(pt.X.multiply(zi))
        val y  = fp(pt.Y.multiply(zi))
        val out = ByteArray(32)
        var yTmp = y
        for (i in 0 until 32) { out[i] = yTmp.and(java.math.BigInteger.valueOf(0xFF)).toByte(); yTmp = yTmp.shiftRight(8) }
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun deriveW(myName: ByteArray, theirName: ByteArray, pw: ByteArray, role: Byte): java.math.BigInteger {
        fun le8(n: Int): ByteArray { val b = ByteArray(8); var v = n.toLong(); for (i in 0..7) { b[i]=(v and 0xFF).toByte(); v=v shr 8 }; return b }
        val sha = java.security.MessageDigest.getInstance("SHA-512")
        sha.update("SPAKE2 a PAKE".toByteArray(Charsets.UTF_8))
        sha.update(byteArrayOf(role))
        sha.update(le8(myName.size)); sha.update(myName)
        sha.update(le8(theirName.size)); sha.update(theirName)
        sha.update(le8(pw.size)); sha.update(pw)
        val digest = sha.digest().reversedArray()
        return java.math.BigInteger(1, digest).mod(Q)
    }

    private fun deriveKey(myName: ByteArray, theirName: ByteArray, pw: ByteArray,
                           xStarBytes: ByteArray, yStarBytes: ByteArray, kBytes: ByteArray): String {
        fun le8(n: Int): ByteArray { val b = ByteArray(8); var v = n.toLong(); for (i in 0..7) { b[i]=(v and 0xFF).toByte(); v=v shr 8 }; return b }
        val sha = java.security.MessageDigest.getInstance("SHA-512")
        sha.update("SPAKE2 a PAKE".toByteArray(Charsets.UTF_8))
        sha.update(le8(myName.size)); sha.update(myName)
        sha.update(le8(theirName.size)); sha.update(theirName)
        sha.update(le8(pw.size)); sha.update(pw)
        sha.update(le8(xStarBytes.size)); sha.update(xStarBytes)
        sha.update(le8(yStarBytes.size)); sha.update(yStarBytes)
        sha.update(le8(kBytes.size)); sha.update(kBytes)
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    data class Session(val x: java.math.BigInteger, val w: java.math.BigInteger,
                        val xStarBytes: ByteArray, val myName: ByteArray,
                        val theirName: ByteArray)

    fun init(code: String): Pair<Session, String> {
        val pw        = code.toByteArray(Charsets.UTF_8)
        val myName    = "adb pair client".toByteArray()
        val theirName = "adb pair server".toByteArray()

        val w = deriveW(myName, theirName, pw, 0x00)
        val x = (java.math.BigInteger(256, java.security.SecureRandom())
                     .mod(Q.subtract(java.math.BigInteger.ONE))).add(java.math.BigInteger.ONE)

        val BASE = Pt(Bx, By, java.math.BigInteger.ONE, Bx.multiply(By).mod(P))
        val M    = decode(M_BYTES)!!
        val xStar = add(mul(x, BASE), mul(w, M))
        val xStarBytes = encode(xStar)

        return Session(x, w, xStarBytes, myName, theirName) to
               xStarBytes.joinToString("") { "%02x".format(it) }
    }

    fun finish(sess: Session, serverHex: String, code: String): String {
        return try {
            val yStarBytes = serverHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (yStarBytes.size != 32) return "ERR:BAD_SIZE_${yStarBytes.size}"

            val yStar = decode(yStarBytes) ?: return "ERR:BAD_POINT"
            val BASE  = Pt(Bx, By, java.math.BigInteger.ONE, Bx.multiply(By).mod(P))
            val N     = decode(N_BYTES) ?: return "ERR:BAD_N"

            val wN = mul(sess.w, N)
            val K  = mul(sess.x, add(yStar, neg(wN)))
            val kBytes = encode(K)

            deriveKey(sess.myName, sess.theirName,
                       code.toByteArray(Charsets.UTF_8),
                       sess.xStarBytes, yStarBytes, kBytes)
        } catch (e: Exception) { "ERR:SPAKE2_EX_${e.message?.take(40)}" }
    }
}

@RequiresApi(30)
class GameAdbBridge(private val ctx: Context) {

    private fun g(key: String): String {
        val saved = ctx.getSharedPreferences("eaquel_locale", Context.MODE_PRIVATE)
            .getString("lang", "system") ?: "system"
        val lang = if (saved == "system") java.util.Locale.getDefault().language else saved
        return s(ctx, lang, key)
    }

    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key        = GameAdbKey.getOrCreate(ctx)
    private var conn: AdbConnection? = null
    private var mdnsJob: Job?        = null
    private var watchdogJob: Job?    = null
    private val reconnectCount       = AtomicInteger(0)

    private val _adb    = MutableStateFlow(AdbState())
    val adb: StateFlow<AdbState> = _adb.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var resolvedPairingHost: String = "127.0.0.1"

    fun startPairingScan() {
        mdnsJob?.cancel()
        _adb.value = AdbState(
            status      = AdbState.Status.SCAN_PAIRING,
            pairingStep = g("msg_pairing_port_scanning")
        )
        sendScanningNotification()
        mdnsJob = scope.launch(Dispatchers.IO) {
            val selfIp = getDeviceIp()
            if (selfIp.isNullOrBlank()) { emitErr(g("msg_wifi_ip_fail")); return@launch }
            resolvedPairingHost = selfIp

            val propPort = readPairingPortFromProps()
            if (propPort > 0) { onPairingPortFound(selfIp, propPort); return@launch }

            val resultCh = Channel<Pair<String, Int>>(1)
            val nsdJob = launch {
                val nsd = ctx.getSystemService(NsdManager::class.java) ?: return@launch
                val ch  = Channel<Pair<String, Int>>(Channel.UNLIMITED)
                val li  = makeDiscoveryListener(nsd, ch)
                nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, li)
                try {
                    withTimeoutOrNull(12_000L) {
                        while (true) {
                            val v = ch.receive()
                            if (v.second in 1..65535) { resultCh.trySend(v); break }
                        }
                    }
                } finally {
                    runCatching { nsd.stopServiceDiscovery(li) }
                    ch.close()
                }
            }
            val nativeJob = launch {
                val port = nativeScanPairingPort(selfIp, 2000)
                if (port > 0) resultCh.trySend(selfIp to port)
            }
            val result = withTimeoutOrNull(13_000L) { resultCh.receive() }
            nsdJob.cancel(); nativeJob.cancel(); resultCh.close()
            if (result != null) {
                resolvedPairingHost = result.first
                onPairingPortFound(result.first, result.second)
            } else {
                emitErr(g("msg_adb_port_not_found"))
            }
        }
    }

    private fun onPairingPortFound(host: String, port: Int) {
        _adb.value = AdbState(
            status      = AdbState.Status.IDLE,
            pairingPort = port,
            pairingStep = "Port bulundu ($port) — Kodu girin"
        )
        sendPairingNotification(host, port)
    }

    private fun readPairingPortFromProps(): Int {
        for (k in arrayOf("service.adb.tls.port", "persist.adb.tls.port")) {
            val p = try {
                android.provider.Settings.Global.getString(ctx.contentResolver, k)
                    ?.toIntOrNull() ?: -1
            } catch (_: Exception) { -1 }
            if (p in 1..65535) return p
        }
        return -1
    }

    fun sendScanningNotification() {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(NOTIF_ID_PAIR + 2)
        val pi = android.app.PendingIntent.getActivity(
            ctx, NOTIF_ID_PAIR + 2,
            Intent(ctx, GameActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(NOTIF_ID_PAIR + 2,
            androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CH_PAIR)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(g("msg_pairing_port_scanning"))
                .setContentText(g("pair_step3"))
                .setProgress(0, 0, true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true).setAutoCancel(false).setContentIntent(pi).build()
        )
    }

    private fun sendPairingNotification(host: String, port: Int) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(NOTIF_ID_PAIR + 2)
        val openPi = android.app.PendingIntent.getActivity(
            ctx, NOTIF_ID_PAIR,
            Intent(ctx, GameActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("auto_pair_host", host); putExtra("auto_pair_port", port)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val replyPi = android.app.PendingIntent.getBroadcast(
            ctx, NOTIF_ID_PAIR + 1,
            Intent(ctx, PairCodeReceiver::class.java).apply {
                action = ACTION_PAIR_CODE
                putExtra("host", host); putExtra("port", port)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val pairAction = androidx.core.app.NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, g("btn_pair"), replyPi
        ).addRemoteInput(
            androidx.core.app.RemoteInput.Builder(EXTRA_PAIR_CODE)
                .setLabel(g("pair_code")).build()
        ).build()
        nm.notify(NOTIF_ID_PAIR,
            androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CH_PAIR)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(g("msg_notif_pair_found"))
                .setContentText("$host:$port  |  ${g("pair_code")}")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText("${g("pair_step3")}\n${g("pair_step4")}\n\n$host  Port $port"))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true).setContentIntent(openPi).addAction(pairAction).build()
        )
    }

    fun cancelPairingNotification() {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID_PAIR); nm.cancel(NOTIF_ID_PAIR + 2)
    }

    private fun saveLastConnection(host: String, port: Int) {
        ctx.getSharedPreferences("eaquel_adb_conn", Context.MODE_PRIVATE).edit()
            .putString("last_host", host).putInt("last_port", port)
            .putLong("last_time", System.currentTimeMillis()).apply()
    }

    fun tryAutoReconnect() {
        val prefs = ctx.getSharedPreferences("eaquel_adb_conn", Context.MODE_PRIVATE)
        val host  = prefs.getString("last_host", null) ?: return
        val port  = prefs.getInt("last_port", -1)
        if (port <= 0) return
        if (System.currentTimeMillis() - prefs.getLong("last_time", 0L) > 24 * 3600 * 1000L) return
        mdnsJob?.cancel()
        mdnsJob = scope.launch(Dispatchers.IO) {
            _adb.value = _adb.value.copy(
                status = AdbState.Status.CONNECTING,
                pairingStep = g("msg_reconnecting")
            )
            doConnect(host, port)
            if (!isConnected()) {
                val newPort = pollConnectPort(host)
                if (newPort > 0) doConnect(host, newPort)
            }
        }
    }

    fun scanMdns() {
        if (mdnsJob?.isActive == true) return
        _adb.value = AdbState(AdbState.Status.SCANNING)
        mdnsJob = scope.launch(Dispatchers.IO) {
            val selfIp = getDeviceIp()
            if (selfIp.isNullOrBlank()) { emitErr(g("msg_wifi_ip_fail")); return@launch }

            val settingsPort = tryReadAdbPortFromSettings()
            if (settingsPort > 0) {
                _adb.value = AdbState(AdbState.Status.FOUND, settingsPort, selfIp)
                doConnect(selfIp, settingsPort); return@launch
            }
            val propPort = nativeGetAdbPort()
            if (propPort > 0) {
                _adb.value = AdbState(AdbState.Status.FOUND, propPort, selfIp)
                doConnect(selfIp, propPort); return@launch
            }

            val resultCh = Channel<Pair<String, Int>>(1)
            val nsdJob = launch {
                val nsd = ctx.getSystemService(NsdManager::class.java) ?: return@launch
                val ch  = Channel<Pair<String, Int>>(Channel.UNLIMITED)
                val li  = makeDiscoveryListener(nsd, ch)
                nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, li)
                try {
                    withTimeoutOrNull(11_000L) {
                        while (true) {
                            val v = ch.receive()
                            if (v.second in 1..65535) { resultCh.trySend(v); break }
                        }
                    }
                } finally {
                    runCatching { nsd.stopServiceDiscovery(li) }
                    ch.close()
                }
            }
            val nativeJob = launch {
                val p = nativeScanAdbPort(selfIp, 2000)
                if (p > 0) resultCh.trySend(selfIp to p)
            }
            val result = withTimeoutOrNull(12_000L) { resultCh.receive() }
            nsdJob.cancel(); nativeJob.cancel(); resultCh.close()
            if (result != null) {
                _adb.value = AdbState(AdbState.Status.FOUND, result.second, result.first)
                doConnect(result.first, result.second)
            } else {
                emitErr(g("msg_adb_port_not_found"))
            }
        }
    }

    fun stopScan() {
        runCatching { nativeCancelScan() }
        mdnsJob?.cancel(); mdnsJob = null
        val cur = _adb.value.status
        if (cur in listOf(
                AdbState.Status.SCANNING, AdbState.Status.SCAN_PAIRING,
                AdbState.Status.PAIRING,  AdbState.Status.CONNECTING)) {
            _adb.value = AdbState()
        }
    }

    private fun tryReadAdbPortFromSettings(): Int {
        try {
            val p = android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_port", -1)
            if (p in 1..65535) return p
        } catch (_: Exception) {}
        try {
            for (f in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
                var found = -1
                java.io.File(f).forEachLine { line ->
                    if (found > 0) return@forEachLine
                    val parts = line.trim().split(" +".toRegex())
                    if (parts.size >= 4 && parts[3] == "0A") {
                        val port = parts[1].substringAfterLast(":").toIntOrNull(16) ?: return@forEachLine
                        if (port in 30000..65535) found = port
                    }
                }
                if (found > 0) return found
            }
        } catch (_: Exception) {}
        val p = nativeGetAdbPort()
        return if (p > 0) p else -1
    }

    fun pair(port: Int, code: String) {
        val effectivePort = if (port > 0) port else _adb.value.pairingPort
        if (effectivePort !in 1..65535) {
            scope.launch { emitErr(g("msg_no_valid_port")) }; return
        }
        val cleanCode = code.filter { it.isDigit() }
        if (cleanCode.length != 6) {
            scope.launch { emitErr("6 haneli kod girin") }; return
        }
        mdnsJob?.cancel(); mdnsJob = null
        val host = resolvedPairingHost.ifBlank { "127.0.0.1" }
        scope.launch {
            _adb.value = _adb.value.copy(
                status = AdbState.Status.PAIRING,
                pairingStep = "Bağlanılıyor $host:$effectivePort…"
            )
            val result = kotlinPair(host, effectivePort, cleanCode, key.pubBytes)
            if (result == "OK") {
                _adb.value = _adb.value.copy(status = AdbState.Status.PAIRED, pairingStep = "Eşleşti!")
                _events.emit("paired")
                val connectPort = pollConnectPort(host)
                if (connectPort > 0) doConnect(host, connectPort)
                else emitErr(g("msg_adb_port_not_found"))
            } else {
                emitErr(mapErrorCode(result))
            }
        }
    }

    fun pairWithAutoScan(code: String) {
        val cleanCode = code.filter { it.isDigit() }
        if (cleanCode.length != 6) {
            scope.launch { emitErr(g("msg_code_required")) }; return
        }
        mdnsJob?.cancel()
        mdnsJob = scope.launch(Dispatchers.IO) {
            val host = getDeviceIp() ?: run { emitErr(g("msg_wifi_ip_fail")); return@launch }
            if (host == "127.0.0.1") { emitErr(g("msg_wifi_ip_fail")); return@launch }

            _adb.value = _adb.value.copy(
                status = AdbState.Status.SCAN_PAIRING,
                pairingStep = "${g("msg_pairing_port_scanning")} ($host)"
            )
            sendScanningNotification()

            var pairingPort = readPairingPortFromProps().also {
                if (it > 0) resolvedPairingHost = host
            }

            if (pairingPort <= 0) {
                val resultCh = Channel<Pair<String, Int>>(1)
                val nsdJob = launch {
                    val nsd = ctx.getSystemService(NsdManager::class.java) ?: return@launch
                    val ch  = Channel<Pair<String, Int>>(Channel.UNLIMITED)
                    val li  = makeDiscoveryListener(nsd, ch)
                    nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, li)
                    try {
                        withTimeoutOrNull(12_000L) {
                            while (true) {
                                val v = ch.receive()
                                if (v.second in 1..65535) { resultCh.trySend(v); break }
                            }
                        }
                    } finally { runCatching { nsd.stopServiceDiscovery(li) }; ch.close() }
                }
                val nativeJob = launch {
                    val p = nativeScanPairingPort(host, 3000)
                    if (p > 0) resultCh.trySend(host to p)
                }
                val found = withTimeoutOrNull(13_000L) { resultCh.receive() }
                nsdJob.cancel(); nativeJob.cancel(); resultCh.close()
                if (found != null) {
                    pairingPort = found.second
                    resolvedPairingHost = found.first
                }
            }

            if (pairingPort <= 0) {
                cancelPairingNotification()
                emitErr(g("msg_no_valid_port"))
                return@launch
            }

            resolvedPairingHost = resolvedPairingHost.ifBlank { host }
            _adb.value = _adb.value.copy(
                pairingPort = pairingPort,
                pairingStep = "${g("msg_port_found")}: $resolvedPairingHost:$pairingPort",
                status = AdbState.Status.PAIRING
            )
            sendPairingNotification(resolvedPairingHost, pairingPort)

            var pairResult = try {
                nativePair(resolvedPairingHost, pairingPort, cleanCode, key.pubBytes)
            } catch (_: UnsatisfiedLinkError) { "ERR:NATIVE_UNAVAILABLE" }

            android.util.Log.d("EaquelPair", "nativePair=$pairResult host=$resolvedPairingHost port=$pairingPort")

            if (pairResult != "OK") {
                android.util.Log.d("EaquelPair", "Kotlin fallback başlıyor")
                for (attempt in 1..3) {
                    pairResult = kotlinPair(resolvedPairingHost, pairingPort, cleanCode, key.pubBytes)
                    android.util.Log.d("EaquelPair", "kotlin attempt=$attempt result=$pairResult")
                    if (pairResult == "OK") break
                    if (attempt < 3 && (pairResult.startsWith("ERR:PORT_CLOSED") || pairResult.startsWith("ERR:CONNECT"))) {
                        delay(800L)
                        val fresh = nativeScanPairingPort(resolvedPairingHost, 2000)
                        if (fresh > 0 && fresh != pairingPort) pairingPort = fresh
                    } else break
                }
            }

            cancelPairingNotification()

            if (pairResult == "OK") {
                _adb.value = _adb.value.copy(status = AdbState.Status.PAIRED, pairingStep = "Eşleşti!")
                _events.emit("paired")
                saveLastConnection(resolvedPairingHost, pairingPort)
                delay(1000L)
                val connectPort = pollConnectPort(resolvedPairingHost)
                if (connectPort > 0) doConnect(resolvedPairingHost, connectPort)
                else emitErr(g("msg_adb_port_not_found"))
            } else {
                emitErr(mapErrorCode(pairResult))
            }
        }
    }

    private fun mapErrorCode(result: String): String = when {
        result.contains("WRONG_CODE")         -> "Kod yanlış — tekrar deneyin"
        result.contains("PORT_CLOSED")        -> g("msg_port_closed")
        result.contains("TLS")                -> "TLS el sıkışması başarısız"
        result.contains("TIMEOUT")            -> "Zaman aşımı"
        result.contains("SPAKE2_UNAVAILABLE") -> "SPAKE2 kütüphanesi yok"
        result.contains("PEER_INFO_DECRYPT")  -> "Sunucu PeerInfo şifresi çözülemedi"
        else -> result
    }

    fun connectManual(host: String, port: Int) {
        if (host.isBlank() || port !in 1..65535) {
            scope.launch { emitErr(g("msg_invalid_addr").replace("{addr}", "$host:$port")) }; return
        }
        runCatching { nativeCancelScan() }
        mdnsJob?.cancel(); mdnsJob = null; watchdogJob?.cancel()
        conn?.close(); conn = null
        scope.launch {
            _adb.value = AdbState(AdbState.Status.CONNECTING, port, host)
            doConnect(host, port)
        }
    }

    private fun makeDiscoveryListener(nsd: NsdManager, ch: Channel<Pair<String, Int>>) =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(s: String, c: Int) = Unit
            override fun onStopDiscoveryFailed(s: String, c: Int)  = Unit
            override fun onDiscoveryStarted(s: String)             = Unit
            override fun onDiscoveryStopped(s: String)             = Unit
            override fun onServiceLost(i: NsdServiceInfo)          = Unit
            override fun onServiceFound(info: NsdServiceInfo)      = resolveWithRetry(nsd, info, ch, 3)
        }

    private fun resolveWithRetry(nsd: NsdManager, info: NsdServiceInfo,
                                  ch: Channel<Pair<String, Int>>, retries: Int) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                if (retries > 0) scope.launch { delay(600L); resolveWithRetry(nsd, info, ch, retries - 1) }
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val rawHost = resolved.host?.hostAddress ?: "127.0.0.1"
                val host = when {
                    rawHost.contains('%') -> rawHost.substringBefore('%')
                    rawHost.contains(':') -> "127.0.0.1"
                    else -> rawHost
                }
                var port = resolved.port
                if (port <= 0) {
                    try {
                        resolved.attributes?.get("port")?.let { bytes ->
                            port = String(bytes, Charsets.UTF_8).trim().toIntOrNull() ?: 0
                        }
                    } catch (_: Exception) {}
                }
                if (port in 1..65535) scope.launch { ch.trySend(host to port) }
                else if (retries > 0) scope.launch { delay(800L); resolveWithRetry(nsd, info, ch, retries - 1) }
                else scope.launch { val p = nativeScanAdbPort(host, 700); if (p > 0) ch.trySend(host to p) }
            }
        })
    }

    @Suppress("DEPRECATION")
    internal suspend fun kotlinPair(
        host: String, port: Int, code: String, pubBytes: ByteArray
    ): String {
        val cleanCode = code.filter { it.isDigit() }
        if (cleanCode.length != 6) return "ERR:INVALID_CODE"

        var kotlinSession: KotlinSpake2.Session? = null
        val clientMsgHex = withContext(Dispatchers.IO) {
            try {
                val r = nativeSpake2Init(cleanCode)
                if (r.startsWith("ERR:")) {
                    val (sess, hex) = KotlinSpake2.init(cleanCode)
                    kotlinSession = sess; hex
                } else r
            } catch (_: UnsatisfiedLinkError) {
                val (sess, hex) = KotlinSpake2.init(cleanCode)
                kotlinSession = sess; hex
            }
        }
        if (clientMsgHex.startsWith("ERR:")) return clientMsgHex

        val clientMsgBytes   = clientMsgHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val capturedSession  = kotlinSession

        return withContext(Dispatchers.IO) {

            val sslSock = try {
                android.util.Log.d("EaquelPair", "SSL bağlantısı: $host:$port")

                val sock = (key.pairingSSLContext.socketFactory.createSocket(host, port) as SSLSocket)
                configureSslSocket(sock).apply {
                    tcpNoDelay = true
                    soTimeout  = 12_000
                    startHandshake()
                    android.util.Log.d("EaquelPair", "TLS TAMAM cipher=${session?.cipherSuite}")
                }
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                android.util.Log.e("EaquelPair", "TLS hatası: ${e.message}")
                return@withContext "ERR:TLS_HANDSHAKE_FAILED"
            } catch (e: java.net.ConnectException) {
                return@withContext if (e.message?.contains("refused") == true)
                    "ERR:PORT_CLOSED" else "ERR:CONNECT_FAILED"
            } catch (_: java.net.SocketTimeoutException) {
                return@withContext "ERR:CONNECT_TIMEOUT"
            } catch (e: Exception) {
                android.util.Log.e("EaquelPair", "Bağlantı hatası: ${e.message}")
                return@withContext "ERR:CONNECT_FAILED"
            }

            try {
                val ins  = sslSock.inputStream
                val outs = sslSock.outputStream

                fun readPacket(): Pair<Int, ByteArray> {
                    val hdr = ByteArray(6); var got = 0
                    while (got < 6) {
                        val n = ins.read(hdr, got, 6 - got)
                        if (n < 0) throw java.io.IOException("Header okurken EOF")
                        got += n
                    }
                    if (hdr[0] != 0x01.toByte())
                        throw java.io.IOException("Geçersiz paket versiyonu: 0x${hdr[0].toInt().and(0xFF).toString(16)}")
                    val type = hdr[1].toInt() and 0xFF
                    val payLen = ((hdr[2].toInt() and 0xFF) shl 24) or
                                 ((hdr[3].toInt() and 0xFF) shl 16) or
                                 ((hdr[4].toInt() and 0xFF) shl  8) or
                                  (hdr[5].toInt() and 0xFF)
                    if (payLen < 0 || payLen > 65536) throw java.io.IOException("Geçersiz payload uzunluğu: $payLen")
                    val payload = ByteArray(payLen); got = 0
                    while (got < payLen) {
                        val n = ins.read(payload, got, payLen - got)
                        if (n < 0) throw java.io.IOException("Payload okurken EOF")
                        got += n
                    }
                    return type to payload
                }
                fun writePacket(type: Int, payload: ByteArray) {
                    val pkt = ByteArray(6 + payload.size)
                    pkt[0] = 0x01; pkt[1] = type.toByte()
                    pkt[2] = ((payload.size shr 24) and 0xFF).toByte()
                    pkt[3] = ((payload.size shr 16) and 0xFF).toByte()
                    pkt[4] = ((payload.size shr  8) and 0xFF).toByte()
                    pkt[5] = ( payload.size         and 0xFF).toByte()
                    System.arraycopy(payload, 0, pkt, 6, payload.size)
                    outs.write(pkt); outs.flush()
                }

                android.util.Log.d("EaquelPair", "İstemci SPAKE2 gönderiliyor (${clientMsgBytes.size} byte)")
                writePacket(0, clientMsgBytes)

                android.util.Log.d("EaquelPair", "Sunucu SPAKE2 bekleniyor...")
                val (srvType, srvSpake) = readPacket()
                android.util.Log.d("EaquelPair", "Sunucu SPAKE2 alındı type=$srvType size=${srvSpake.size}")
                if (srvType != 0) return@withContext "ERR:UNEXPECTED_SPAKE2_TYPE($srvType)"

                val srvHex = srvSpake.joinToString("") { "%02x".format(it) }
                val sharedKeyHex = if (capturedSession == null) {
                    try { nativeSpake2Finish(srvHex) }
                    catch (_: UnsatisfiedLinkError) { KotlinSpake2.finish(
                        KotlinSpake2.init(cleanCode).first, srvHex, cleanCode) }
                } else {
                    KotlinSpake2.finish(capturedSession, srvHex, cleanCode)
                }
                if (sharedKeyHex.contains("WRONG_CODE")) return@withContext "ERR:WRONG_CODE"
                if (sharedKeyHex.startsWith("ERR:"))     return@withContext sharedKeyHex

                val sharedBytes  = sharedKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val cliMat = hkdfSha256(sharedBytes, "clientpairingpacket".toByteArray(), 20)
                val srvMat = hkdfSha256(sharedBytes, "serverpairingpacket".toByteArray(), 20)
                val cliKey = cliMat.slice(0..15).toByteArray()
                val cliIv  = cliMat.slice(16..19).toByteArray()
                val srvKey = srvMat.slice(0..15).toByteArray()
                val srvIv  = srvMat.slice(16..19).toByteArray()

                val kPeerInfoDataSize = 8192
                val peerInfo = ByteArray(1 + kPeerInfoDataSize) 
                peerInfo[0]  = 0x00 
                val copyLen  = minOf(pubBytes.size, kPeerInfoDataSize)
                System.arraycopy(pubBytes, 0, peerInfo, 1, copyLen) 

                val encPeerInfo = aesGcmEncrypt(cliKey, cliIv, 0L, peerInfo)

                android.util.Log.d("EaquelPair", "PeerInfo gönderiliyor (${encPeerInfo.size} byte)")
                writePacket(1, encPeerInfo) 

                android.util.Log.d("EaquelPair", "Sunucu PeerInfo bekleniyor...")
                val (peerType, encSrvPeer) = readPacket()
                android.util.Log.d("EaquelPair", "Sunucu PeerInfo alındı type=$peerType size=${encSrvPeer.size}")
                if (peerType != 1) return@withContext "ERR:UNEXPECTED_PEER_INFO_TYPE($peerType)"

                val decSrv = aesGcmDecrypt(srvKey, srvIv, 0L, encSrvPeer)
                if (decSrv == null) {
                    android.util.Log.e("EaquelPair", "Sunucu PeerInfo şifresi çözülemedi")
                    return@withContext "ERR:PEER_INFO_DECRYPT_FAILED"
                }

                android.util.Log.i("EaquelPair", "EŞLEŞİM BAŞARILI ✓ $host:$port")
                "OK"
            } catch (e: java.io.IOException) {
                android.util.Log.e("EaquelPair", "IO: ${e.message}")
                "ERR:PORT_CLOSED"
            } catch (e: Exception) {
                android.util.Log.e("EaquelPair", "İstisna: ${e.javaClass.simpleName}: ${e.message}")
                "ERR:${e.javaClass.simpleName}"
            } finally {
                runCatching { sslSock.close() }
            }
        }
    }

    private suspend fun doConnect(host: String, port: Int) {
        try {
            conn?.close(); conn = null
            _adb.value = AdbState(AdbState.Status.CONNECTING, port, host)
            val c = AdbConnection(host, port, key, 6000)
            c.connect()
            conn = c
            saveLastConnection(host, port)
            _adb.value = AdbState(AdbState.Status.CONNECTED, port, host)
            _events.emit("connected:$host:$port")
            startWatchdog(host, port)
        } catch (e: Exception) {
            android.util.Log.e("EaquelBridge", "doConnect hatası: ${e.message}")
            _adb.value = AdbState(AdbState.Status.ERROR, error = e.message ?: "Bağlantı hatası")
        }
    }

    private fun startWatchdog(host: String, port: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(8000L)
                val c = conn
                if (c == null || !c.isAlive()) {
                    android.util.Log.w("EaquelBridge", "Watchdog: bağlantı kesildi, yeniden bağlanılıyor...")
                    _events.emit("disconnected")
                    if (reconnectCount.incrementAndGet() <= 5) doConnect(host, port)
                    else { emitErr("Yeniden bağlantı sınırı aşıldı"); break }
                } else {
                    reconnectCount.set(0)
                }
            }
        }
    }

    private suspend fun pollConnectPort(host: String): Int {
        repeat(8) { i ->
            delay(if (i == 0) 1200L else 800L)
            val p = tryReadAdbPortFromSettings()
            if (p > 0) return p
            val np = nativeGetAdbPort()
            if (np > 0) return np
        }
        return -1
    }

    fun isConnected(): Boolean = conn?.isAlive() == true

    fun exec(cmd: String): Pair<Int, String> = try {
        conn?.shell(cmd) ?: (-1 to "Bağlı değil")
    } catch (e: Exception) { -1 to e.message.orEmpty() }

    fun execStr(cmd: String) = exec(cmd).second.trim()

    fun disconnect() {
        watchdogJob?.cancel(); watchdogJob = null
        conn?.close(); conn = null
        _adb.value = AdbState()
        scope.launch { _events.emit("disconnected") }
    }

    fun destroy() {
        stopScan(); disconnect()
        scope.cancel()
    }

    fun getClickBarrierType(): Int     = try { nativeDetectClickBarrier() } catch (_: Exception) { 0 }
    fun isGestureCoordSafe(x: Int, y: Int, screenW: Int, screenH: Int): Boolean =
        try { nativeCheckGestureCoord(x, y, screenW, screenH) } catch (_: Exception) { false }

    fun initPairing() {

        try { System.loadLibrary("crypto") } catch (_: UnsatisfiedLinkError) {}
        try { System.loadLibrary("ssl")    } catch (_: UnsatisfiedLinkError) {}
        try { nativeInitPairing() } catch (_: UnsatisfiedLinkError) {}
    }

    fun startServer(): Boolean {
        return isConnected()
    }

    fun getDeviceIpPublic(): String? = getDeviceIp()

    fun run(cmd: String): String = execStr(cmd)

    private fun getDeviceIp(): String? {
        try { val ip = nativeGetWifiIp(); if (ip.isNotBlank()) return ip } catch (_: Exception) {}
        try {
            val wm = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION")
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
        } catch (_: Exception) {}
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private suspend fun emitErr(msg: String) {
        _adb.value = AdbState(AdbState.Status.ERROR, error = msg)
        _events.emit("error:$msg")
    }

    private external fun nativePair(host: String, port: Int, code: String, pubBytes: ByteArray): String
    private external fun nativeSpake2Init(pwd: String): String
    private external fun nativeSpake2Finish(serverMsg: String): String
    private external fun nativeGetWifiIp(): String
    private external fun nativeGetAdbPort(): Int
    private external fun nativeScanAdbPort(host: String, timeoutMs: Int): Int
    private external fun nativeScanPairingPort(host: String, timeoutMs: Int): Int
    private external fun nativeCancelScan()
    private external fun nativeDetectClickBarrier(): Int
    private external fun nativeCheckGestureCoord(x: Int, y: Int, screenW: Int, screenH: Int): Boolean
    private external fun nativeIsPairingReady(): Boolean
    private external fun nativeRunPairingTests(): String
    private external fun nativeInitPairing()

    companion object {
        init { try { System.loadLibrary("gamecore") } catch (_: UnsatisfiedLinkError) {} }
    }
  }
