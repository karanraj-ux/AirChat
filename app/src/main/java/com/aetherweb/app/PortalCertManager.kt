package com.aetherweb.app

import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Voice Room Phase 0 — HTTPS for the web portal.
 *
 * Browsers hard-require a secure context for getUserMedia (microphone). A guest opening
 * http://<hotspot-ip>:8080 gets the mic BLOCKED. The fix: serve the portal over HTTPS with a
 * self-signed certificate generated at runtime. The guest taps "Advanced -> Proceed" once;
 * the cert stays stable for the whole app session so they are not re-prompted.
 *
 * Additive: the plain-HTTP connector on :8080 is untouched; this only adds :8443 (HTTPS).
 */
object PortalCertManager {
    private const val TAG = "PortalCertManager"
    const val KEY_ALIAS = "aetherweb-portal"
    const val HTTPS_PORT = 8443

    // NOTE: self-signed throwaway cert for a local offline portal; not a secret.
    private val KEYSTORE_PASSWORD: CharArray = "aetherweb-local-portal".toCharArray()

    @Volatile
    private var cached: KeyStore? = null

    /** Returns the (session-stable) in-memory PKCS12 keystore holding the portal cert. */
    @Synchronized
    fun getOrCreateKeyStore(hostIp: String): KeyStore {
        cached?.let { return it }
        val ks = generateSelfSigned(hostIp)
        cached = ks
        return ks
    }

    fun keyStorePassword(): CharArray = KEYSTORE_PASSWORD

    private fun generateSelfSigned(hostIp: String): KeyStore {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)

            val kpg = KeyPairGenerator.getInstance("RSA", provider)
            kpg.initialize(2048, SecureRandom())
            val keyPair = kpg.generateKeyPair()

            val now = Date()
            val expiry = Date(now.time + 10L * 365L * 24L * 60L * 60L * 1000L) // 10 years
            val serial = BigInteger(64, SecureRandom())
            val dn = X500Name("CN=$hostIp, O=AirChat, OU=Local Voice Portal")

            val builder = JcaX509v3CertificateBuilder(
                dn, serial, now, expiry, dn, keyPair.public
            )
            // SANs: include hostIp, loopback, and all active device IP addresses
            val ips = mutableSetOf<String>()
            if (hostIp.isNotBlank()) ips.add(hostIp)
            ips.add("127.0.0.1")
            ips.add("10.0.2.15") // Android emulator gateway IP
            ips.add("192.168.49.1") // Wi-Fi Direct Hotspot IP
            ips.add("192.168.43.1") // Standard Android SoftAP IP
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            addr.hostAddress?.let { if (it.isNotBlank()) ips.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {}

            val sanNames = mutableListOf<GeneralName>()
            for (ip in ips) {
                try {
                    sanNames.add(GeneralName(GeneralName.iPAddress, ip))
                } catch (_: Exception) {}
            }
            sanNames.add(GeneralName(GeneralName.dNSName, "localhost"))
            sanNames.add(GeneralName(GeneralName.dNSName, "nexus.run"))

            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sanNames.toTypedArray()))
            builder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            builder.addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(provider)
                .build(keyPair.private)
            val holder = builder.build(signer)
            val cert: X509Certificate = JcaX509CertificateConverter()
                .setProvider(provider)
                .getCertificate(holder)
            cert.verify(keyPair.public) // self-check before serving

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(cert))
            Log.d(TAG, "Generated self-signed portal cert for $hostIp (valid 10y)")
            return ks
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate portal cert", e)
            throw e
        }
    }
}
