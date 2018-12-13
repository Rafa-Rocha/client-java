package eu.arrowhead.common.misc;

import eu.arrowhead.common.api.ArrowheadConverter;
import eu.arrowhead.common.exception.ErrorMessage;
import eu.arrowhead.common.exception.ExceptionType;
import eu.arrowhead.common.model.RawTokenInfo;
import org.apache.log4j.Logger;

import javax.crypto.Cipher;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

public class SecurityVerifier {
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    public interface ThrowingResponseSupplier<E extends Exception> {
        Response.ResponseBuilder get() throws E;
    }

    protected final Logger log = Logger.getLogger(getClass());
    private PrivateKey privateKey;
    private PublicKey authKey;

    public static SecurityVerifier createFromProperties() {
        return createFromProperties(ArrowheadProperties.loadDefault());
    }

    public static SecurityVerifier createFromProperties(ArrowheadProperties props) {
        return create(props.getKeystore(), props.getKeystorePass(), props.getKeyPass(), props.getAuthKey(),
                props.isSecure());
    }

    private static SecurityVerifier create(String keystore, String keystorepass, String keypass, String authKeyPath,
                                           boolean secure) {
        if (secure) {
            return new SecurityVerifier()
                    .loadProviderPrivateKey(keystore, keystorepass, keypass)
                    .loadAuthorizationPublicKey(authKeyPath);
        } else {
            return new SecurityVerifier();
        }
    }

    private SecurityVerifier() {
    }

    private SecurityVerifier loadProviderPrivateKey(String keystore, String keystorepass, String keypass) {
        KeyStore keyStore = SecurityUtils.loadKeyStore(keystore, keystorepass);
        return loadProviderPrivateKey(keyStore, keypass);
    }

    private SecurityVerifier loadProviderPrivateKey(KeyStore keyStore, String keypass) {
        privateKey = SecurityUtils.getPrivateKey(keyStore, keypass);
        return this;
    }


    private SecurityVerifier loadAuthorizationPublicKey(String keyPath) {
        authKey = SecurityUtils.getPublicKey(keyPath);
        log.info("Authorization System PublicKey Base64: " + Base64.getEncoder().encodeToString(authKey.getEncoded()));
        return this;
    }

    public  <T> Response verifiedResponse(SecurityContext context, String token, String signature, T readout) {
        return context.isSecure() ?
                verifyRequester(context, token, signature, () -> Response.status(200).entity(readout)) :
                Response.status(200).entity(readout).build();
    }

    public <T, E extends Exception> Response verifiedResponse(SecurityContext context, String token, String signature, ThrowingSupplier<T, E> onOk) {
        final ThrowingResponseSupplier<RuntimeException> throwingResponseSupplier = () -> {
            try {
                return Response.status(200).entity(onOk.get());
            } catch (Exception ex) {
                log.warn("Replying with error message", ex);
                ErrorMessage error = new ErrorMessage("Internal Server Error: " + ex.getMessage(), 500, null, Utility.class.toString());
                return Response.status(500).entity(error);
            }
        };
        return verifiedResponse(context, token, signature, throwingResponseSupplier);
    }

    public Response verifiedResponse(SecurityContext context, String token, String signature) {
        return context.isSecure() ?
                verifyRequester(context, token, signature, null) :
                Response.status(200).build();
    }

    public <E extends Exception> Response verifiedResponse(SecurityContext context, String token, String signature, ThrowingResponseSupplier<E> onOk) {
        try {
            return context.isSecure() ?
                    verifyRequester(context, token, signature, onOk) :
                    onOk != null ? onOk.get().build() : Response.status(200).build();
        } catch (Exception ex) {
            log.warn("Replying with error message", ex);
            ErrorMessage error = new ErrorMessage("Internal Server Error: " + ex.getMessage(), 500, null, Utility.class.toString());
            return Response.status(500).entity(error).build();
        }
    }

    private <E extends Exception> Response verifyRequester(SecurityContext context, String token, String signature, ThrowingResponseSupplier<E> onOk) {
        try {
            String commonName = SecurityUtils.getCertCNFromSubject(context.getUserPrincipal().getName());
            String[] commonNameParts = commonName.split("\\.");
            String consumerName = commonNameParts[0];

            if (token.contains(" ")) {
                token = token.replaceAll("\\s", "+");
            }
            if (signature.contains(" ")) {
                signature = signature.replaceAll("\\s", "+");
            }

            byte[] tokenbytes = Base64.getDecoder().decode(token);
            byte[] signaturebytes = Base64.getDecoder().decode(signature);

            SecurityUtils.addSecurityProvider();
            Signature signatureInstance = Signature.getInstance("SHA256withRSA", "BC");
            signatureInstance.initVerify(authKey);
            signatureInstance.update(tokenbytes);

            boolean verifies = signatureInstance.verify(signaturebytes);
            if (!verifies) {
                ErrorMessage error = new ErrorMessage("Token validation failed", 401, ExceptionType.AUTH, Utility.class.toString());
                return Response.status(401).entity(error).build();
            }

            Cipher cipher = Cipher.getInstance("RSA/NONE/PKCS1Padding", "BC");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            //Check if the provider public key registered in the database is the same as the one used by the provider at the moment
            byte[] byteToken = cipher.doFinal(tokenbytes);

            String json = new String(byteToken, StandardCharsets.UTF_8);
            RawTokenInfo rawTokenInfo = ArrowheadConverter.json().fromString(json, RawTokenInfo.class);
            String[] rawTokenInfoParts = rawTokenInfo.getC().split("\\.");
            String consumerTokenName = rawTokenInfoParts[0];

            long endTime = rawTokenInfo.getE();
            long currentTime = System.currentTimeMillis();

            if (consumerName.equalsIgnoreCase(consumerTokenName)) {
                if (endTime == 0L || (endTime > currentTime)) {
                    if (onOk != null) return onOk.get().build();
                    else return Response.status(200).build();
                }
                ErrorMessage error = new ErrorMessage("Authorization token has expired", 401, ExceptionType.AUTH, Utility.class.toString());
                return Response.status(401).entity(error).build();

            } else {
                log.warn("Token and provider name mismatch: " + consumerName + " " + consumerTokenName);
                ErrorMessage error = new ErrorMessage("Permission denied", 401, ExceptionType.AUTH, null);
                return Response.status(401).entity(error).build();
            }

        } catch (Exception ex) {
            log.warn("Replying with error message", ex);
            ErrorMessage error = new ErrorMessage("Internal Server Error: " + ex.getMessage(), 500, null, Utility.class.toString());
            return Response.status(500).entity(error).build();
        }
    }
}
