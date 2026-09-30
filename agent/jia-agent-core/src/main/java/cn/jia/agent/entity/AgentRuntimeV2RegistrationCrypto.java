package cn.jia.agent.entity;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** X25519/HKDF/AES-GCM sealing. Production entry points always use CSPRNG. */
public final class AgentRuntimeV2RegistrationCrypto {
    private static final byte[] SALT = AgentRuntimeV2RegistrationFingerprints.sha256(
            "CYF-RUNTIME-V2-X25519-HKDF-SALT-V1".getBytes(StandardCharsets.US_ASCII));
    private static final SecureRandom RANDOM = new SecureRandom();
    private AgentRuntimeV2RegistrationCrypto() {}

    /**
     * Builds canonical AAD after the production ephemeral public key and nonce exist.
     * Implementations must include both byte strings in the returned AAD.
     */

    @FunctionalInterface
    public interface AadFactory {
        byte[] create(byte[] serverEphemeralPublicKeySpki, byte[] nonce);
    }

    public record Sealed(byte[] serverEphemeralPublicKeySpki, byte[] nonce, byte[] sealed) {
        public Sealed {
            serverEphemeralPublicKeySpki = serverEphemeralPublicKeySpki.clone();
            nonce = nonce.clone(); sealed = sealed.clone();
            if (serverEphemeralPublicKeySpki.length != 44 || nonce.length != 12 || sealed.length != 48) {
                throw new IllegalArgumentException("sealed registration proof has invalid lengths");
            }
        }
        @Override public byte[] serverEphemeralPublicKeySpki(){return serverEphemeralPublicKeySpki.clone();}
        @Override public byte[] nonce(){return nonce.clone();}@Override public byte[] sealed(){return sealed.clone();}
    }

    public static Sealed sealVerifier(byte[] plaintext32, byte[] processPublicKeySpki, AadFactory aadFactory) {
        return sealRandom(plaintext32, processPublicKeySpki, aadFactory,
                "CYF-RUNTIME-V2-REGISTRATION-VERIFIER-V1\0");
    }
    public static Sealed sealChallenge(byte[] plaintext32, byte[] processPublicKeySpki, AadFactory aadFactory) {
        return sealRandom(plaintext32, processPublicKeySpki, aadFactory,
                "CYF-RUNTIME-V2-RECONNECT-CHALLENGE-V1\0");
    }
    public static byte[] openVerifier(byte[] sealed48, byte[] nonce12, byte[] aad,
            byte[] processPrivateKeyPkcs8, byte[] serverPublicKeySpki) {
        return open(sealed48,nonce12,aad,processPrivateKeyPkcs8,serverPublicKeySpki,
                "CYF-RUNTIME-V2-REGISTRATION-VERIFIER-V1\0");
    }
    public static byte[] openChallenge(byte[] sealed48, byte[] nonce12, byte[] aad,
            byte[] processPrivateKeyPkcs8, byte[] serverPublicKeySpki) {
        return open(sealed48,nonce12,aad,processPrivateKeyPkcs8,serverPublicKeySpki,
                "CYF-RUNTIME-V2-RECONNECT-CHALLENGE-V1\0");
    }

    /** Test-only deterministic vector hook; production code must call sealVerifier/sealChallenge. */
    static Sealed sealForTest(byte[] plaintext32, byte[] processPublicKeySpki, byte[] aad,
            byte[] serverPrivateKeyPkcs8, byte[] nonce12, boolean challenge) {
        try {
            PrivateKey privateKey=KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(serverPrivateKeyPkcs8));
            PublicKey publicKey=KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(processPublicKeySpki));
            PublicKey serverPublic=derivePublicFromPrivateForTest(privateKey, serverPrivateKeyPkcs8);
            return seal(plaintext32,aad,privateKey,publicKey,serverPublic.getEncoded(),nonce12,
                    challenge?"CYF-RUNTIME-V2-RECONNECT-CHALLENGE-V1\0":"CYF-RUNTIME-V2-REGISTRATION-VERIFIER-V1\0");
        } catch (Exception e) { throw new IllegalStateException("deterministic test sealing failed",e); }
    }

    private static Sealed sealRandom(byte[] plaintext32, byte[] processSpki, AadFactory aadFactory,
            String domain) {
        if (aadFactory == null) {
            throw new IllegalArgumentException("AAD factory is required");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
            KeyPair pair = generator.generateKeyPair();
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            byte[] serverPublicSpki = pair.getPublic().getEncoded();
            byte[] aad = aadFactory.create(serverPublicSpki.clone(), nonce.clone());
            if (aad == null) {
                throw new IllegalArgumentException("AAD factory returned null");
            }
            PublicKey process = KeyFactory.getInstance("X25519")
                    .generatePublic(new X509EncodedKeySpec(processSpki));
            return seal(plaintext32, aad, pair.getPrivate(), process, serverPublicSpki, nonce, domain);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("registration sealing failed", e);
        }
    }
    private static Sealed seal(byte[] plaintext32,byte[] aad,PrivateKey privateKey,PublicKey peer,byte[] publicSpki,byte[] nonce,String domain)throws Exception{if(plaintext32==null||plaintext32.length!=32||nonce==null||nonce.length!=12)throw new IllegalArgumentException("registration proof lengths are invalid");byte[] shared=agree(privateKey,peer);byte[] key=hkdf(shared,info(domain,aad));Arrays.fill(shared,(byte)0);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);byte[] sealed=cipher.doFinal(plaintext32);Arrays.fill(key,(byte)0);return new Sealed(publicSpki,nonce,sealed);}
    private static byte[] open(byte[] sealed48,byte[] nonce12,byte[] aad,byte[] privatePkcs8,byte[] serverSpki,String domain){try{if(sealed48==null||sealed48.length!=48||nonce12==null||nonce12.length!=12)throw new IllegalArgumentException("sealed proof lengths are invalid");KeyFactory factory=KeyFactory.getInstance("X25519");PrivateKey privateKey=factory.generatePrivate(new PKCS8EncodedKeySpec(privatePkcs8));PublicKey server=factory.generatePublic(new X509EncodedKeySpec(serverSpki));byte[] shared=agree(privateKey,server);byte[] key=hkdf(shared,info(domain,aad));Arrays.fill(shared,(byte)0);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce12));cipher.updateAAD(aad);byte[] value=cipher.doFinal(sealed48);Arrays.fill(key,(byte)0);if(value.length!=32)throw new IllegalStateException("decrypted proof length is invalid");return value;}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("sealed registration proof is invalid",e);}}
    private static byte[] agree(PrivateKey own,PublicKey peer)throws Exception{KeyAgreement agreement=KeyAgreement.getInstance("X25519");agreement.init(own);agreement.doPhase(peer,true);byte[] shared=agreement.generateSecret();int aggregate=0;for(byte b:shared)aggregate|=b;if(aggregate==0){Arrays.fill(shared,(byte)0);throw new IllegalArgumentException("all-zero X25519 shared secret rejected");}return shared;}
    private static byte[] info(String domain,byte[] aad){byte[] prefix=domain.getBytes(StandardCharsets.US_ASCII);byte[] digest=AgentRuntimeV2RegistrationFingerprints.sha256(aad);byte[] info=Arrays.copyOf(prefix,prefix.length+digest.length);System.arraycopy(digest,0,info,prefix.length,digest.length);return info;}
    private static byte[] hkdf(byte[] ikm,byte[] info)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SALT,"HmacSHA256"));byte[] prk=mac.doFinal(ikm);mac.init(new SecretKeySpec(prk,"HmacSHA256"));mac.update(info);mac.update((byte)1);byte[] okm=mac.doFinal();Arrays.fill(prk,(byte)0);return okm;}
    private static PublicKey derivePublicFromPrivateForTest(PrivateKey privateKey,byte[] pkcs8)throws Exception{byte[] scalar=Arrays.copyOfRange(pkcs8,pkcs8.length-32,pkcs8.length);byte[] baseSpki=java.util.HexFormat.of().parseHex("302a300506032b656e0321000900000000000000000000000000000000000000000000000000000000000000");PublicKey base=KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(baseSpki));byte[] publicRaw=agree(privateKey,base);byte[] spki=java.util.HexFormat.of().parseHex("302a300506032b656e0321000000000000000000000000000000000000000000000000000000000000000000");System.arraycopy(publicRaw,0,spki,12,32);Arrays.fill(scalar,(byte)0);return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(spki));}
}
