package io.orvanta.pay.security;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.Config;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single sign-on with an OpenID Connect provider (Keycloak, Entra ID, Okta and the like): the authorization
 * code flow, the provider's endpoints from its discovery document, and the ID token checked against the
 * provider's published keys. Only RS256 tokens are accepted. The provider authenticates the person; who they
 * are in Orvanta (the user, its roles and limits) stays in Orvanta: the user named by the token's claim must
 * exist and be active here. Settings:
 *
 * <pre>
 * sso:
 *   enabled: true
 *   issuer: https://login.example.com/realms/payments     # the discovery document is at issuer/.well-known/openid-configuration
 *   clientId: orvanta-console
 *   clientSecret: ${env.ORVANTA_SSO_CLIENT_SECRET:-}
 *   redirectUri: https://orvanta.example.com/api/auth/sso/callback
 *   usernameClaim: preferred_username                     # the claim that holds the Orvanta user name
 *   label: Sign in with the bank's account                # the button in the Console
 * </pre>
 */
public final class OpenIdConnect {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String usernameClaim;
    private final String label;
    private volatile Rec discovery;
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

    public OpenIdConnect(String issuer, String clientId, String clientSecret, String redirectUri, String usernameClaim, String label) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.usernameClaim = usernameClaim == null ? "preferred_username" : usernameClaim;
        this.label = label == null ? "Sign in with single sign-on" : label;
    }

    /** @return the provider from the settings, or null when single sign-on is off */
    public static OpenIdConnect fromConfig(Config config) {
        if (!config.getBool("sso.enabled", false)) {
            return null;
        }
        for (String key : List.of("issuer", "clientId", "clientSecret", "redirectUri")) {
            if (config.get("sso." + key, null) == null || config.get("sso." + key, "").isBlank()) {
                throw new IllegalStateException("sso.enabled is true, so sso." + key + " must be set");
            }
        }
        return new OpenIdConnect(config.get("sso.issuer", null), config.get("sso.clientId", null), config.get("sso.clientSecret", null),
                config.get("sso.redirectUri", null), config.get("sso.usernameClaim", "preferred_username"), config.get("sso.label", null));
    }

    public String label() {
        return label;
    }

    public String issuer() {
        return issuer;
    }

    /** Where the browser is sent to sign in; the state and nonce come back with the answer. */
    public String authorizationUrl(String state, String nonce) {
        return discovery().str("authorization_endpoint") + "?response_type=code&scope=" + enc("openid profile email")
                + "&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri) + "&state=" + enc(state) + "&nonce=" + enc(nonce);
    }

    /**
     * Turns the code the provider sent back into the user name: the code is exchanged for an ID token at the
     * token endpoint (client secret as HTTP basic credentials), and the token's signature and claims are checked.
     *
     * @throws IllegalArgumentException when the provider refuses the code or the token does not hold up
     */
    public String signIn(String code, String nonce) {
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri) + "&client_id=" + enc(clientId);
        String basic = Base64.getEncoder().encodeToString((enc(clientId) + ":" + enc(clientSecret)).getBytes(StandardCharsets.UTF_8));
        Rec answer;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(discovery().str("token_endpoint")))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .header("Authorization", "Basic " + basic).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalArgumentException("the provider refused the code (" + r.statusCode() + ")");
            }
            answer = Json.parse(r.body());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalArgumentException("the provider could not be reached: " + e.getMessage(), e);
        }
        Rec claims = verify(answer.str("id_token"), nonce);
        String username = Ops.str(claims.get(usernameClaim));
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("the token has no '" + usernameClaim + "' claim");
        }
        return username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The claims of an ID token this provider signed for this client, for this sign-in, and still valid. */
    Rec verify(String idToken, String nonce) {
        if (idToken == null) {
            throw new IllegalArgumentException("the provider sent no ID token");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("the ID token is malformed");
        }
        Rec header = Json.parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.str("alg"))) {
            throw new IllegalArgumentException("the ID token is signed with " + header.str("alg") + "; only RS256 is accepted");
        }
        PublicKey key = key(header.str("kid"));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new IllegalArgumentException("the ID token's signature is wrong");
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("the ID token's signature cannot be checked: " + e.getMessage(), e);
        }
        Rec claims = Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        if (!issuer.equals(claims.str("iss"))) {
            throw new IllegalArgumentException("the ID token is from " + claims.str("iss") + ", not " + issuer);
        }
        Object aud = claims.get("aud");
        boolean forUs = aud instanceof List<?> list ? list.contains(clientId) : clientId.equals(Ops.str(aud));
        if (!forUs) {
            throw new IllegalArgumentException("the ID token is for another client");
        }
        if (!(claims.get("exp") instanceof Number exp) || exp.longValue() < System.currentTimeMillis() / 1000) {
            throw new IllegalArgumentException("the ID token has expired");
        }
        if (nonce == null || !nonce.equals(claims.str("nonce"))) {
            throw new IllegalArgumentException("the ID token does not belong to this sign-in");
        }
        return claims;
    }

    private PublicKey key(String kid) {
        PublicKey known = kid == null ? null : keys.get(kid);
        if (known != null) {
            return known;
        }
        // an unknown key id: the provider may have rotated its keys, so the set is read again, once
        Rec set = fetch(discovery().str("jwks_uri"));
        for (Object k : Ops.list(set.get("keys"))) {
            if (k instanceof Rec jwk && "RSA".equals(jwk.str("kty")) && jwk.str("n") != null && jwk.str("e") != null) {
                try {
                    BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.str("n")));
                    BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.str("e")));
                    keys.put(jwk.str("kid") == null ? "" : jwk.str("kid"), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
                } catch (java.security.GeneralSecurityException | IllegalArgumentException ignored) {
                    // a key that cannot be read is not used
                }
            }
        }
        PublicKey key = keys.get(kid == null ? "" : kid);
        if (key == null) {
            throw new IllegalArgumentException("the provider publishes no key " + kid);
        }
        return key;
    }

    private Rec discovery() {
        Rec d = discovery;
        if (d == null) {
            d = fetch(issuer + "/.well-known/openid-configuration");
            for (String key : List.of("authorization_endpoint", "token_endpoint", "jwks_uri")) {
                if (d.str(key) == null) {
                    throw new IllegalArgumentException("the provider's discovery document has no " + key);
                }
            }
            discovery = d;
        }
        return d;
    }

    private static Rec fetch(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalArgumentException(url + " answered " + r.statusCode());
            }
            return Json.parse(r.body());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalArgumentException(url + " could not be read: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
