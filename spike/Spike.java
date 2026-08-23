// FlyShare Android spike — does a JVM stack do TLS 1.3 with an external PSK,
// interoperably with Node, and fast enough?
//
//   java -cp "lib/*" Spike.java crypto  <vectors.json>
//   java -cp "lib/*" Spike.java client  <host> <port> <pskBase64Url>
//   java -cp "lib/*" Spike.java server  <port> <pskBase64Url>
//
// Deliberately plain Java: the question is about the TLS stack, not about
// Kotlin or Android, and keeping it to one file makes it runnable anywhere.

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.Vector;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;

public class Spike {

    static final byte[] PSK_IDENTITY = "flyshare".getBytes(StandardCharsets.US_ASCII);

    static byte[] b64u(String s) { return Base64.getUrlDecoder().decode(s); }
    static String b64u(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    /** Set by --aes: prefer AES-GCM, which the platform can accelerate. */
    static boolean preferAes = false;

    /** Recorded from the peer callback; the protocol's own context is protected. */
    static volatile int negotiatedSuite = 0;

    public static void main(String[] args) throws Exception {
        // JcaTlsCrypto hands the record cipher to the JCE, and no stock
        // provider knows BouncyCastle's name for ChaCha20-Poly1305.
        Security.addProvider(new BouncyCastleProvider());
        for (String a : args) if (a.equals("--aes")) preferAes = true;

        switch (args[0]) {
            case "crypto" -> crypto(args[1]);
            case "client" -> client(args[1], Integer.parseInt(args[2]), b64u(args[3]));
            case "server" -> server(Integer.parseInt(args[1]), b64u(args[2]));
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    // ---- part 1: does BouncyCastle's crypto agree with Node's? --------------

    static void crypto(String vectorsPath) throws Exception {
        String json = Files.readString(Path.of(vectorsPath));

        byte[] idAPriv = b64u(field(json, "identityA", "private"));
        byte[] idBPub = b64u(field(json, "identityB", "public"));
        byte[] ephAPriv = b64u(field(json, "ephemeralA", "private"));
        byte[] ephBPub = b64u(field(json, "ephemeralB", "public"));

        byte[] identityShared = x25519(idAPriv, idBPub);
        byte[] ephemeralShared = x25519(ephAPriv, ephBPub);

        int failures = 0;
        failures += check("X25519 identity agreement",
                b64u(identityShared), simple(json, "identityAxB"));
        failures += check("X25519 ephemeral agreement",
                b64u(ephemeralShared), simple(json, "ephemeralAxB"));

        // PROTOCOL.md §7.2 — key hashed as text, nonce as bytes.
        String pubB = simple(json, "publicKey");
        String nonceB = simple(json, "nonce");
        SHA256Digest sha = new SHA256Digest();
        update(sha, "flyshare-sas-commit-v2".getBytes(StandardCharsets.UTF_8));
        update(sha, pubB.getBytes(StandardCharsets.UTF_8));
        update(sha, b64u(nonceB));
        byte[] commit = new byte[32];
        sha.doFinal(commit, 0);
        failures += check("pairing commitment", b64u(commit), simple(json, "expected"));

        // PROTOCOL.md §7.3
        String nonceA = simple(json, "initiatorNonce");
        String pubA = simple(json, "initiatorPublic");
        byte[] salt = concat(b64u(nonceA), b64u(nonceB));
        byte[] info = ("flyshare-sas-v2|" + pubA + "|" + pubB).getBytes(StandardCharsets.UTF_8);
        byte[] material = hkdf(identityShared, salt, info, 4);
        long code = ((long) (material[0] & 0xff) << 24 | (material[1] & 0xff) << 16
                | (material[2] & 0xff) << 8 | (material[3] & 0xff)) % 1000000L;
        failures += check("six-digit code", String.format("%06d", code), sasExpected(json));

        // PROTOCOL.md §8.2
        String idA = simple(json, "deviceIdA");
        String idB = simple(json, "deviceIdB");
        String ordered = idA.compareTo(idB) <= 0 ? idA + "|" + idB : idB + "|" + idA;
        byte[] psk = hkdf(ephemeralShared, identityShared,
                ("flyshare-session-v2|" + ordered).getBytes(StandardCharsets.UTF_8), 32);
        failures += check("session PSK", b64u(psk), simple(json, "expectedPsk"));

        System.out.println(failures == 0
                ? "\nBouncyCastle derives every value identically to Node."
                : "\n" + failures + " mismatch(es).");
        System.exit(failures == 0 ? 0 : 1);
    }

    static byte[] x25519(byte[] privateKey, byte[] publicKey) {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(new X25519PrivateKeyParameters(privateKey, 0));
        byte[] out = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(new X25519PublicKeyParameters(publicKey, 0), out, 0);
        return out;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        generator.generateBytes(out, 0, length);
        return out;
    }

    // ---- part 2: TLS 1.3 external PSK, client side --------------------------

    static void client(String host, int port, byte[] psk) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            TlsClientProtocol protocol =
                    new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
            TlsCrypto crypto = new JcaTlsCryptoProvider().create(new SecureRandom());

            long handshakeStart = System.nanoTime();
            protocol.connect(new PskClient(crypto, psk));
            long handshakeMs = (System.nanoTime() - handshakeStart) / 1_000_000;

            System.out.println("handshake ok in " + handshakeMs + " ms, suite 0x"
                    + Integer.toHexString(negotiatedSuite));
            pumpAndReport(protocol.getInputStream(), protocol.getOutputStream(), "client");
        }
    }

    static class PskClient extends DefaultTlsClient {
        final byte[] psk;
        PskClient(TlsCrypto crypto, byte[] psk) { super(crypto); this.psk = psk; }

        @Override public ProtocolVersion[] getProtocolVersions() {
            return ProtocolVersion.TLSv13.only();
        }
        @Override protected int[] getSupportedCipherSuites() {
            // SHA-256 suites only. An external PSK is bound to one hash
            // (tls13_hkdf_sha256 here); offering a SHA-384 suite lets the
            // server pick a different digest, and the peer rejects the
            // handshake with "ciphersuite digest has changed".
            return preferAes
                ? new int[]{ CipherSuite.TLS_AES_128_GCM_SHA256 }
                : new int[]{ CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                             CipherSuite.TLS_AES_128_GCM_SHA256 };
        }
        @Override public void notifySelectedCipherSuite(int selectedCipherSuite) {
            negotiatedSuite = selectedCipherSuite;
            super.notifySelectedCipherSuite(selectedCipherSuite);
        }
        @Override public Vector getExternalPSKs() {
            Vector<TlsPSKExternal> v = new Vector<>();
            v.add(new BasicTlsPSKExternal(PSK_IDENTITY, getCrypto().createSecret(psk),
                    PRFAlgorithm.tls13_hkdf_sha256));
            return v;
        }
        // Never reached: a PSK-only TLS 1.3 handshake exchanges no certificate.
        @Override public TlsAuthentication getAuthentication() {
            throw new UnsupportedOperationException("no certificates in a PSK handshake");
        }
    }

    // ---- part 3: TLS 1.3 external PSK, server side (the experimental one) ---

    static void server(int port, byte[] psk) throws Exception {
        try (ServerSocket listener = new ServerSocket(port)) {
            System.out.println("listening on " + port);
            try (Socket socket = listener.accept()) {
                socket.setTcpNoDelay(true);
                TlsServerProtocol protocol =
                        new TlsServerProtocol(socket.getInputStream(), socket.getOutputStream());
                TlsCrypto crypto = new JcaTlsCryptoProvider().create(new SecureRandom());

                long handshakeStart = System.nanoTime();
                protocol.accept(new PskServer(crypto, psk));
                long handshakeMs = (System.nanoTime() - handshakeStart) / 1_000_000;

                System.out.println("handshake ok in " + handshakeMs + " ms, suite 0x"
                        + Integer.toHexString(negotiatedSuite));
                pumpAndReport(protocol.getInputStream(), protocol.getOutputStream(), "server");
            }
        }
    }

    static class PskServer extends DefaultTlsServer {
        final byte[] psk;
        PskServer(TlsCrypto crypto, byte[] psk) { super(crypto); this.psk = psk; }

        @Override public ProtocolVersion[] getProtocolVersions() {
            return ProtocolVersion.TLSv13.only();
        }
        @Override protected int[] getSupportedCipherSuites() {
            // SHA-256 suites only. An external PSK is bound to one hash
            // (tls13_hkdf_sha256 here); offering a SHA-384 suite lets the
            // server pick a different digest, and the peer rejects the
            // handshake with "ciphersuite digest has changed".
            return preferAes
                ? new int[]{ CipherSuite.TLS_AES_128_GCM_SHA256 }
                : new int[]{ CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                             CipherSuite.TLS_AES_128_GCM_SHA256 };
        }
        @Override public int getSelectedCipherSuite() throws IOException {
            negotiatedSuite = super.getSelectedCipherSuite();
            return negotiatedSuite;
        }
        @Override public TlsPSKExternal getExternalPSK(Vector identities) {
            return new BasicTlsPSKExternal(PSK_IDENTITY, getCrypto().createSecret(psk),
                    PRFAlgorithm.tls13_hkdf_sha256);
        }
        @Override public TlsCredentials getCredentials() {
            return null; // no certificate: the PSK is the authentication
        }
    }

    // ---- shared: echo a byte count back, and measure the stream -------------

    /**
     * Reads everything the peer sends, then reports how fast it arrived. The
     * peer sends a fixed volume and closes, so the read loop ends on EOF.
     */
    static void pumpAndReport(InputStream in, OutputStream out, String role) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        long start = System.nanoTime();
        int read;
        while ((read = in.read(buffer)) > 0) total += read;
        double seconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("%s received %.1f MiB in %.2fs = %.0f MiB/s%n",
                role, total / 1048576.0, seconds, total / 1048576.0 / Math.max(seconds, 1e-9));
        try { out.close(); } catch (IOException ignored) { }
    }

    // ---- crude JSON reading, enough for a flat vectors file ----------------

    static int check(String label, String actual, String expected) {
        boolean ok = actual.equals(expected);
        System.out.printf("  %s  %s%s%n", ok ? "PASS" : "FAIL", label,
                ok ? "" : "\n        expected " + expected + "\n        got      " + actual);
        return ok ? 0 : 1;
    }

    static void update(SHA256Digest digest, byte[] bytes) { digest.update(bytes, 0, bytes.length); }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** First value of "key": "value" after the given object name. */
    static String field(String json, String object, String key) {
        int at = json.indexOf('"' + object + '"');
        return valueAfter(json, at, key);
    }

    static String simple(String json, String key) { return valueAfter(json, 0, key); }

    static String sasExpected(String json) {
        return valueAfter(json, json.indexOf("\"sas\""), "expected");
    }

    static String valueAfter(String json, int from, String key) {
        int at = json.indexOf('"' + key + '"', Math.max(from, 0));
        if (at < 0) throw new IllegalStateException("no key " + key);
        int colon = json.indexOf(':', at);
        int open = json.indexOf('"', colon);
        int close = json.indexOf('"', open + 1);
        return json.substring(open + 1, close);
    }
}
