package io.orvanta.pay.transport;

import io.orvanta.core.data.Rec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;
import java.util.Iterator;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;

/**
 * OpenPGP for files exchanged with partners over folders and SFTP: what we send is encrypted to the
 * partner's key and signed with ours; what we receive is decrypted with our key and its signature checked
 * against the partner's. Compatible with GnuPG.
 *
 * <pre>
 * pgp:
 *   encryptKeyFile: /run/secrets/partner-public.asc    # outbound: encrypted to this key
 *   signKeyFile: /run/secrets/our-private.asc          # outbound: signed with this key
 *   decryptKeyFile: /run/secrets/our-private.asc       # inbound: decrypted with this key
 *   verifyKeyFile: /run/secrets/partner-public.asc     # inbound: the signature must be by this key
 *   passphrase: ${env.PGP_PASSPHRASE}                  # of our private key
 *   armor: true                                        # ASCII armour (.asc) instead of binary (.gpg)
 * </pre>
 *
 * Inbound, a file that is not encrypted, not signed, or signed by another key is refused when the setting
 * asks for decryption or verification: nothing is taken on trust because it arrived.
 */
public final class Pgp {

    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private Pgp() {
    }

    /** Whether a transport or destination setting asks for OpenPGP. */
    public static boolean wanted(Rec settings) {
        return settings != null && settings.get("pgp") instanceof Rec;
    }

    /** Encrypts to the partner's key and signs with ours (either may be left out), as the setting says. */
    public static byte[] protect(Rec settings, byte[] plain, String fileName) throws IOException {
        Rec pgp = settings.rec("pgp");
        try {
            PGPPublicKey encryptTo = pgp.str("encryptKeyFile") == null ? null : encryptionKey(Path.of(pgp.str("encryptKeyFile")));
            PGPSecretKey signWith = pgp.str("signKeyFile") == null ? null : signingKey(Path.of(pgp.str("signKeyFile")));
            if (encryptTo == null && signWith == null) {
                throw new IOException("the pgp setting of an outbound channel names 'encryptKeyFile', 'signKeyFile' or both");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream out = Boolean.TRUE.equals(pgp.get("armor")) ? new ArmoredOutputStream(bytes) : bytes;
            OutputStream encrypted = out;
            PGPEncryptedDataGenerator encryptor = null;
            if (encryptTo != null) {
                encryptor = new PGPEncryptedDataGenerator(new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                        .setWithIntegrityPacket(true).setSecureRandom(RANDOM).setProvider("BC"));
                encryptor.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encryptTo).setProvider("BC"));
                encrypted = encryptor.open(out, new byte[1 << 16]);
            }
            PGPCompressedDataGenerator compressor = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
            OutputStream compressed = compressor.open(encrypted);
            PGPSignatureGenerator signer = null;
            if (signWith != null) {
                PGPPrivateKey privateKey = signWith.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider("BC")
                        .build(passphrase(pgp)));
                signer = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(signWith.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider("BC"),
                        signWith.getPublicKey());
                signer.init(PGPSignature.BINARY_DOCUMENT, privateKey);
                signer.generateOnePassVersion(false).encode(compressed);
            }
            PGPLiteralDataGenerator literal = new PGPLiteralDataGenerator();
            try (OutputStream data = literal.open(compressed, PGPLiteralData.BINARY, fileName, new Date(), new byte[1 << 16])) {
                data.write(plain);
            }
            if (signer != null) {
                signer.update(plain);
                signer.generate().encode(compressed);
            }
            compressed.close();
            if (encryptor != null) {
                encrypted.close();
            }
            out.close();
            return bytes.toByteArray();
        } catch (PGPException e) {
            throw new IOException("OpenPGP failed: " + e.getMessage(), e);
        }
    }

    /** Decrypts with our key and checks the signature with the partner's, as the setting says; refuses what does not fit. */
    public static byte[] open(Rec settings, byte[] data) throws IOException {
        Rec pgp = settings.rec("pgp");
        try {
            JcaPGPObjectFactory factory = new JcaPGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(data)));
            Object next = factory.nextObject();
            PGPPublicKeyEncryptedData encrypted = null;
            if (next instanceof PGPEncryptedDataList list) {
                if (pgp.str("decryptKeyFile") == null) {
                    throw new IOException("the file is encrypted, but the channel has no 'decryptKeyFile'");
                }
                PGPSecretKeyRingCollection ours = new PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(Files.newInputStream(Path.of(pgp.str("decryptKeyFile")))),
                        new JcaKeyFingerprintCalculator());
                PGPPrivateKey privateKey = null;
                for (Iterator<?> it = list.getEncryptedDataObjects(); it.hasNext() && privateKey == null;) {
                    Object o = it.next();
                    if (o instanceof PGPPublicKeyEncryptedData pked) {
                        PGPSecretKey secret = ours.getSecretKey(pked.getKeyID());
                        if (secret != null) {
                            privateKey = secret.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passphrase(pgp)));
                            encrypted = pked;
                        }
                    }
                }
                if (privateKey == null) {
                    throw new IOException("the file is not encrypted to any of our keys");
                }
                factory = new JcaPGPObjectFactory(encrypted.getDataStream(new JcePublicKeyDataDecryptorFactoryBuilder().setProvider("BC").build(privateKey)));
                next = factory.nextObject();
            } else if (pgp.str("decryptKeyFile") != null) {
                throw new IOException("the file is not encrypted, and the channel expects it to be");
            }
            if (next instanceof PGPCompressedData compressed) {
                factory = new JcaPGPObjectFactory(compressed.getDataStream());
                next = factory.nextObject();
            }
            PGPOnePassSignature signature = null;
            if (next instanceof PGPOnePassSignatureList signatures) {
                signature = signatures.get(0);
                next = factory.nextObject();
            }
            if (!(next instanceof PGPLiteralData literal)) {
                throw new IOException("the file holds no data in OpenPGP form");
            }
            byte[] plain;
            try (InputStream in = literal.getInputStream()) {
                plain = in.readAllBytes();
            }
            if (pgp.str("verifyKeyFile") != null) {
                if (signature == null) {
                    throw new IOException("the file is not signed, and the channel expects a signature");
                }
                PGPPublicKeyRingCollection theirs = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(Files.newInputStream(Path.of(pgp.str("verifyKeyFile")))),
                        new JcaKeyFingerprintCalculator());
                PGPPublicKey key = theirs.getPublicKey(signature.getKeyID());
                if (key == null) {
                    throw new IOException("the file is signed by a key that is not the partner's");
                }
                signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);
                signature.update(plain);
                Object trailer = factory.nextObject();
                if (!(trailer instanceof PGPSignatureList list) || !signature.verify(list.get(0))) {
                    throw new IOException("the signature of the file does not verify");
                }
            }
            if (encrypted != null && encrypted.isIntegrityProtected() && !encrypted.verify()) {
                throw new IOException("the file was changed after it was encrypted");
            }
            return plain;
        } catch (PGPException e) {
            throw new IOException("OpenPGP failed: " + e.getMessage(), e);
        }
    }

    private static char[] passphrase(Rec pgp) {
        return pgp.str("passphrase") == null ? new char[0] : pgp.str("passphrase").toCharArray();
    }

    /** The partner's key to encrypt to: the first key of the file that can encrypt. */
    static PGPPublicKey encryptionKey(Path file) throws IOException, PGPException {
        PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(Files.newInputStream(file)), new JcaKeyFingerprintCalculator());
        for (PGPPublicKeyRing ring : rings) {
            for (Iterator<PGPPublicKey> keys = ring.getPublicKeys(); keys.hasNext();) {
                PGPPublicKey key = keys.next();
                if (key.isEncryptionKey()) {
                    return key;
                }
            }
        }
        throw new IOException(file + " holds no key that can encrypt");
    }

    /** Our key to sign with: the first key of the file that can sign. */
    static PGPSecretKey signingKey(Path file) throws IOException, PGPException {
        PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(Files.newInputStream(file)), new JcaKeyFingerprintCalculator());
        for (PGPSecretKeyRing ring : rings) {
            for (Iterator<PGPSecretKey> keys = ring.getSecretKeys(); keys.hasNext();) {
                PGPSecretKey key = keys.next();
                if (key.isSigningKey()) {
                    return key;
                }
            }
        }
        throw new IOException(file + " holds no key that can sign");
    }

    /** The text of a decrypted file. */
    public static String text(byte[] plain) {
        return new String(plain, StandardCharsets.UTF_8);
    }
}
