package io.orvanta.pay.security;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Authentication and entitlements. A user has roles, a role is a list of permissions, and every
 * API call names the permission it needs. Tokens carry only the user name; roles are read from
 * the store on each request so a role change or a disabled user takes effect immediately.
 */
public final class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private static final String DUMMY_HASH = Crypto.hashPassword("no such user");

    /** Every permission the platform checks, for the role editor. */
    public static final List<String> PERMISSIONS = List.of(
            "payments.view", "payments.submit", "payments.repair", "payments.cancel", "payments.approve",
            "studio.view", "studio.edit", "studio.approve",
            "admin.view", "admin.edit", "admin.approve",
            Masking.PERMISSION);

    /** Roles that come with the platform; they are written at every start and cannot be changed or removed. */
    public static final Set<String> BUILT_IN_ROLES = Set.of("ADMIN", "OPERATOR", "DESIGNER", "APPROVER", "USER_ADMIN");

    /**
     * The payment data a user may see and act on. Permissions say what a user may do; the scope says on
     * which payments. Empty lists (and no amount) mean no limit. With several limits a payment must match them all:
     * received on one of the channels, from one of the debtor accounts, in one of the currencies, up to the amount.
     */
    public record Scope(List<String> channels, List<String> debtorAccounts, List<String> currencies, java.math.BigDecimal maxAmount) {
        public static final Scope ALL = new Scope(List.of(), List.of(), List.of(), null);

        public static Scope of(Object stored) {
            if (!(stored instanceof Map<?, ?> m)) {
                return ALL;
            }
            Rec rec = Rec.from(m);
            List<String> currencies = new ArrayList<>();
            for (String c : strings(rec.get("currencies"))) {
                currencies.add(c.toUpperCase(java.util.Locale.ROOT));
            }
            return new Scope(strings(rec.get("channels")), strings(rec.get("debtorAccounts")), List.copyOf(currencies),
                    Ops.blank(rec.get("maxAmount")) ? null : Ops.num(rec.get("maxAmount")));
        }

        private static List<String> strings(Object list) {
            List<String> out = new ArrayList<>();
            for (Object o : Ops.list(list)) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return List.copyOf(out);
        }

        public boolean restricted() {
            return !channels.isEmpty() || !debtorAccounts.isEmpty() || !currencies.isEmpty() || maxAmount != null;
        }

        /** Whether the limit is by the content of payments (accounts, currencies, amount) and not only by channel: then whole files are out of reach. */
        public boolean byContent() {
            return !debtorAccounts.isEmpty() || !currencies.isEmpty() || maxAmount != null;
        }

        public boolean allowsTransaction(Rec txn) {
            return (channels.isEmpty() || has(channels, txn.str("channelIn")))
                    && (debtorAccounts.isEmpty() || has(debtorAccounts, Ops.str(txn.at("debtor.account"))))
                    && (currencies.isEmpty() || has(currencies, txn.str("currency")))
                    && (maxAmount == null || (txn.get("amount") != null && Ops.num(txn.get("amount")).compareTo(maxAmount) <= 0));
        }

        /** A received file holds payments of many accounts, currencies and amounts, so a user limited by content sees no files at all. */
        public boolean allowsMessage(Rec message) {
            return !byContent() && (channels.isEmpty() || has(channels, message.str("channel")));
        }

        /** A payment without the value, such as a rejected file that never reached a channel, is outside every limit. */
        private static boolean has(List<String> allowed, String value) {
            return value != null && allowed.contains(value);
        }

        /** Narrows a store filter to the transactions in scope; false when nothing can match. */
        public boolean narrowTransactions(Rec filter) {
            if (maxAmount != null) {
                Object asked = filter.get("amount");
                if (asked == null) {
                    filter.put("amount", Rec.of("lte", maxAmount));
                } else if (!(asked instanceof Map<?, ?>) && Ops.gt(asked, maxAmount)) {
                    return false;
                }
            }
            return narrow(filter, "channelIn", channels) && narrow(filter, "debtor.account", debtorAccounts) && narrow(filter, "currency", currencies);
        }

        public boolean narrowMessages(Rec filter) {
            return !byContent() && narrow(filter, "channel", channels);
        }

        private static boolean narrow(Rec filter, String field, List<String> allowed) {
            if (allowed.isEmpty()) {
                return true;
            }
            Object asked = filter.get(field);
            if (asked == null) {
                filter.put(field, new ArrayList<>(allowed));
                return true;
            }
            return allowed.contains(String.valueOf(asked));
        }

        public Rec toRec() {
            return Rec.of("channels", new ArrayList<>(channels), "debtorAccounts", new ArrayList<>(debtorAccounts),
                    "currencies", new ArrayList<>(currencies), "maxAmount", maxAmount);
        }
    }

    /**
     * @param approvalLimits the most this user may approve a payment action for, per currency ("*" for any currency); empty means no limit.
     *                       A user with several roles has the highest limit of them, and no limit at all when one of the roles has none.
     */
    public record Principal(String username, String displayName, Set<String> roles, Set<String> permissions, Scope scope,
                            java.util.Map<String, java.math.BigDecimal> approvalLimits) {
        public Principal(String username, String displayName, Set<String> roles, Set<String> permissions) {
            this(username, displayName, roles, permissions, Scope.ALL, java.util.Map.of());
        }

        public Principal(String username, String displayName, Set<String> roles, Set<String> permissions, Scope scope) {
            this(username, displayName, roles, permissions, scope, java.util.Map.of());
        }

        /** Whether this user may approve an action on a payment of this amount; a user without limits may approve any. */
        public boolean mayApprove(java.math.BigDecimal amount, String currency) {
            if (approvalLimits == null || approvalLimits.isEmpty() || amount == null) {
                return true;
            }
            java.math.BigDecimal limit = approvalLimits.get(currency == null ? "*" : currency);
            if (limit == null) {
                limit = approvalLimits.get("*");
            }
            return limit != null && amount.compareTo(limit) <= 0;
        }

        public boolean can(String permission) {
            return permissions.contains("*") || permissions.contains(permission);
        }

        public Rec toRec() {
            return Rec.of("username", username, "displayName", displayName, "roles", new ArrayList<>(roles),
                    "permissions", new ArrayList<>(permissions), "scope", scope.restricted() ? scope.toRec() : null,
                    "approvalLimits", approvalLimits == null || approvalLimits.isEmpty() ? null : Rec.from(approvalLimits));
        }
    }

    private final DocStore store;
    private final String secret;
    private final boolean requireMfa;
    private final int tokenMinutes;
    private final int maxFailures;
    private final String bootstrapPassword;
    private final int lockMinutes;
    private volatile String lastLocked;

    public AuthService(Platform platform) {
        this.store = platform.store;
        String configured = platform.config.get("security.jwtSecret", null);
        if (configured == null) {
            LOG.warn("security.jwtSecret is not set: using a random secret, so sessions end at restart and "
                    + "cannot be shared between processes. Set ORVANTA_SECURITY_JWTSECRET.");
        }
        this.secret = configured == null ? Crypto.randomSecret() : configured;
        this.tokenMinutes = platform.config.getInt("security.tokenMinutes", 60);
        this.maxFailures = platform.config.getInt("security.maxFailedLogins", 5);
        this.requireMfa = platform.config.getBool("security.requireMfa", false);
        this.bootstrapPassword = platform.config.get("security.bootstrapPassword", null);
        this.lockMinutes = platform.config.getInt("security.lockMinutes", 15);
    }

    /** Creates the default roles, and the users of the seed file when the store has no users at all. */
    public void seed(Path seedFile) throws Exception {
        // built-in roles are written at every start so a new version's permissions reach existing installations
        role("ADMIN", "Full access", List.of("*"));
        role("OPERATOR", "Submits instructions, follows payments, requests repair and cancellation (maker)",
                List.of("payments.view", "payments.submit", "payments.repair", "payments.cancel", Masking.PERMISSION));
        role("DESIGNER", "Maker of low-code models in Studio", List.of("payments.view", "studio.view", "studio.edit"));
        role("APPROVER", "Checker of model changes, user changes and payment actions",
                List.of("payments.view", "payments.approve", "studio.view", "studio.approve", "admin.view", "admin.approve", Masking.PERMISSION));
        role("USER_ADMIN", "Maker of user changes", List.of("admin.view", "admin.edit"));
        if (store.count(DocStore.USER, null) > 0) {
            return;
        }
        if (!Files.isRegularFile(seedFile)) {
            // no seed file (a container): the first administrator comes from the environment, once
            String password = bootstrapPassword;
            if (password == null) {
                LOG.warn("there are no users and nothing to create one from: set ORVANTA_SECURITY_BOOTSTRAPPASSWORD "
                        + "(at least 12 characters) to create the user 'admin' at the next start");
            } else if (Policies.passwordProblem("admin", password) != null) {
                LOG.error("ORVANTA_SECURITY_BOOTSTRAPPASSWORD was not accepted ({}); no user was created", Policies.passwordProblem("admin", password));
            } else if (store.insertIfAbsent(DocStore.USER, Rec.of("id", "admin", "displayName", "Administrator", "roles", List.of("ADMIN"),
                    "passwordHash", Crypto.hashPassword(password), "status", "ACTIVE", "failedLogins", 0,
                    "createdAt", Platform.now(), "createdBy", "bootstrap"))) {
                LOG.info("created the first user 'admin' from the bootstrap password; sign in and create named users, then unset the variable");
            }
            return;
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(seedFile, StandardCharsets.UTF_8));
        for (Object u : Ops.list(Ops.get(loaded, "users"))) {
            Rec user = Rec.from((Map<?, ?>) u);
            store.save(DocStore.USER, Rec.of("id", user.str("username"), "displayName", user.str("displayName"),
                    "roles", Ops.list(user.get("roles")), "passwordHash", Crypto.hashPassword(user.str("password")),
                    "status", "ACTIVE", "failedLogins", 0, "createdAt", Platform.now(), "createdBy", "seed"));
        }
        LOG.info("seeded users from {}", seedFile);
    }

    private void role(String id, String description, List<String> permissions) {
        store.save(DocStore.ROLE, Rec.of("id", id, "description", description, "permissions", permissions, "builtIn", true));
    }

    /** @return {token, user} or null when the credentials are wrong or the user is locked or disabled */
    public Rec login(String username, String password) {
        return login(username, password, null);
    }

    /**
     * @param code the one-time code of a user who has turned on the second step; null when none was given
     * @return the session; a record with only 'mfaRequired' when the password was right and the code is still missing; null when refused
     */
    public Rec login(String username, String password, String code) {
        Rec user = username == null ? null : store.get(DocStore.USER, username);
        if (user == null) {
            // same work as a real check, so response time does not reveal which user names exist
            Crypto.verifyPassword(String.valueOf(password), DUMMY_HASH);
            return null;
        }
        // a lock placed after too many wrong passwords ends by itself; a lock without an end time needs an administrator
        if ("LOCKED".equals(user.str("status")) && user.str("lockedUntil") != null
                && java.time.Instant.parse(user.str("lockedUntil")).isBefore(java.time.Instant.now())) {
            store.updateIf(DocStore.USER, username, Rec.of("status", "LOCKED"), Rec.of("status", "ACTIVE", "failedLogins", 0));
            user = store.get(DocStore.USER, username);
        }
        if (!"ACTIVE".equals(user.str("status"))) {
            Crypto.verifyPassword(String.valueOf(password), DUMMY_HASH);
            return null;
        }
        boolean second = Boolean.TRUE.equals(user.at("mfa.enabled"));
        if (second && Crypto.verifyPassword(password, user.str("passwordHash")) && (code == null || code.isBlank())) {
            return Rec.of("mfaRequired", true);
        }
        // a code is taken once: the step it belongs to must be later than the last one used
        long step = second ? Crypto.totpStep(Ops.str(user.at("mfa.secret")), code == null ? null : code.trim(), System.currentTimeMillis() / 1000) : 0;
        // or a recovery code, for a user without the device: each one works once
        List<Object> recovery = new ArrayList<>(Ops.list(user.at("mfa.recovery")));
        int usedRecovery = -1;
        if (second && code != null && code.trim().toLowerCase(java.util.Locale.ROOT).matches(RECOVERY_SHAPE) && Crypto.verifyPassword(password, user.str("passwordHash"))) {
            for (int i = 0; i < recovery.size() && usedRecovery < 0; i++) {
                usedRecovery = Crypto.verifyPassword(code.trim().toLowerCase(java.util.Locale.ROOT), Ops.str(recovery.get(i))) ? i : -1;
            }
        }
        boolean codeRight = !second || usedRecovery >= 0 || (step > 0 && (user.at("mfa.lastStep") == null || step > Ops.num(user.at("mfa.lastStep")).longValue()));
        if (!Crypto.verifyPassword(password, user.str("passwordHash")) || !codeRight) {
            int failures = user.get("failedLogins") == null ? 1 : Ops.num(user.get("failedLogins")).intValue() + 1;
            Rec changes = Rec.of("failedLogins", failures);
            if (failures >= maxFailures) {
                changes.put("status", "LOCKED");
                changes.put("lockedUntil", java.time.Instant.now().plusSeconds(lockMinutes * 60L).toString());
                lastLocked = username;
                LOG.warn("user {} locked for {} minutes after {} failed logins", username, lockMinutes, failures);
            }
            store.updateIf(DocStore.USER, username, new Rec(), changes);
            return null;
        }
        Rec signedIn = Rec.of("failedLogins", 0, "lastLoginAt", Platform.now());
        if (usedRecovery >= 0) {
            recovery.remove(usedRecovery);
            signedIn.put("mfa.recovery", recovery);
            signedIn.put("mfa.lastRecoveryAt", Platform.now());
        } else if (second) {
            signedIn.put("mfa.lastStep", step);
        }
        store.updateIf(DocStore.USER, username, new Rec(), signedIn);
        long expires = System.currentTimeMillis() / 1000 + tokenMinutes * 60L;
        // "tv" ties the token to the user's token version: signing out or a credential change makes every older token void
        String token = Crypto.signToken(Rec.of("sub", username, "exp", expires, "tv", tokenVersion(user)), secret);
        Rec session = Rec.of("token", token, "expiresInSeconds", tokenMinutes * 60, "user", principal(user).toRec());
        if (usedRecovery >= 0) {
            session.put("recoveryCodeUsed", true);
            session.put("recoveryCodesLeft", recovery.size());
        }
        return session;
    }

    /**
     * A session for a user an identity provider has just authenticated (single sign-on): no password here, but the
     * user must exist in Orvanta and be active; roles, limits and scope are Orvanta's. The second step is the
     * provider's business, so it is not asked for.
     *
     * @return the session, or null when there is no active user by that name
     */
    public Rec loginExternal(String username) {
        Rec user = username == null ? null : store.get(DocStore.USER, username.trim().toLowerCase(java.util.Locale.ROOT));
        if (user == null || !"ACTIVE".equals(user.str("status"))) {
            return null;
        }
        store.updateIf(DocStore.USER, user.str("id"), new Rec(), Rec.of("failedLogins", 0, "lastLoginAt", Platform.now(), "lastLoginVia", "sso"));
        long expires = System.currentTimeMillis() / 1000 + tokenMinutes * 60L;
        String token = Crypto.signToken(Rec.of("sub", user.str("id"), "exp", expires, "tv", tokenVersion(user)), secret);
        return Rec.of("token", token, "expiresInSeconds", tokenMinutes * 60, "user", principal(user).toRec());
    }

    /** A short-lived signed value that comes back unchanged, or not at all: the state of a sign-in with a provider. */
    public String signState(Rec claims, int seconds) {
        Rec c = claims.copy();
        c.put("exp", System.currentTimeMillis() / 1000 + seconds);
        return Crypto.signToken(c, secret);
    }

    /** @return the claims of a state this process signed and that has not expired, or null */
    public Rec verifyState(String state) {
        return state == null ? null : Crypto.verifyToken(state, secret);
    }

    public Principal authenticate(String token) {
        Rec claims = token == null ? null : Crypto.verifyToken(token, secret);
        if (claims == null) {
            return null;
        }
        Rec user = store.get(DocStore.USER, claims.str("sub"));
        if (user == null || !"ACTIVE".equals(user.str("status"))) {
            return null;
        }
        Object version = claims.get("tv");
        if (!(version instanceof Number n) || n.longValue() != tokenVersion(user)) {
            return null;
        }
        if (requireMfa && !Boolean.TRUE.equals(user.at("mfa.enabled"))) {
            Principal full = principal(user);
            return new Principal(full.username(), full.displayName(), full.roles(), Set.of(), full.scope());
        }
        return principal(user);
    }

    private static long tokenVersion(Rec user) {
        return user.get("tokenVersion") == null ? 0 : Ops.num(user.get("tokenVersion")).longValue();
    }

    /** Whether every user must have the second step (security.requireMfa). A user without it can sign in only to set it up. */
    public boolean mfaRequired() {
        return requireMfa;
    }

    /** What a user sees of their own second step. */
    public Rec mfaState(String username) {
        Rec user = store.get(DocStore.USER, username);
        return Rec.of("enabled", user != null && Boolean.TRUE.equals(user.at("mfa.enabled")), "since", user == null ? null : user.at("mfa.since"), "required", requireMfa,
                "recoveryCodesLeft", user == null ? 0 : Ops.list(user.at("mfa.recovery")).size());
    }

    /** The shape of a recovery code: two groups of four letters and digits that are not easily confused. */
    static final String RECOVERY_SHAPE = "[a-z0-9]{4}-[a-z0-9]{4}";
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final java.security.SecureRandom RECOVERY_RANDOM = new java.security.SecureRandom();

    /** Eight new recovery codes for the user; only their hashes are kept, so they are shown once. */
    private List<String> newRecoveryCodes(String username) {
        List<String> codes = new ArrayList<>();
        List<Object> hashes = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                code.append(RECOVERY_ALPHABET.charAt(RECOVERY_RANDOM.nextInt(RECOVERY_ALPHABET.length())));
                if (i == 3) {
                    code.append('-');
                }
            }
            codes.add(code.toString());
            hashes.add(Crypto.hashPassword(code.toString()));
        }
        store.updateIf(DocStore.USER, username, new Rec(), Rec.of("mfa.recovery", hashes));
        return codes;
    }

    /** A new set of recovery codes, replacing the old, for a user who shows a current code from the app; null when the code is wrong. */
    public List<String> mfaRecoveryCodes(String username, String code) {
        Rec user = store.get(DocStore.USER, username);
        long step = user == null || !Boolean.TRUE.equals(user.at("mfa.enabled")) ? -1
                : Crypto.totpStep(Ops.str(user.at("mfa.secret")), code == null ? null : code.trim(), System.currentTimeMillis() / 1000);
        if (step < 0 || (user.at("mfa.lastStep") != null && step <= Ops.num(user.at("mfa.lastStep")).longValue())) {
            return null;
        }
        store.updateIf(DocStore.USER, username, new Rec(), Rec.of("mfa.lastStep", step));
        return newRecoveryCodes(username);
    }

    /** Starts setting up the second step: a new secret, kept aside until a code from it is shown. */
    public Rec mfaEnroll(String username) {
        Rec user = store.get(DocStore.USER, username);
        if (Boolean.TRUE.equals(user.at("mfa.enabled"))) {
            throw new IllegalStateException("the second step is on already; turn it off first, or ask an administrator to reset it");
        }
        String secret = Crypto.totpSecret();
        store.updateIf(DocStore.USER, username, new Rec(), Rec.of("mfa", Rec.of("pending", secret, "enabled", false)));
        return Rec.of("secret", secret, "uri", "otpauth://totp/Orvanta:" + username + "?secret=" + secret + "&issuer=Orvanta&algorithm=SHA1&digits=6&period=30");
    }

    /**
     * Turns the second step on when the user shows a code from the secret they were given.
     *
     * @return the recovery codes, to be shown once; null when the code is wrong
     */
    public List<String> mfaConfirm(String username, String code) {
        Rec user = store.get(DocStore.USER, username);
        String pending = user == null ? null : Ops.str(user.at("mfa.pending"));
        long step = Crypto.totpStep(pending, code == null ? null : code.trim(), System.currentTimeMillis() / 1000);
        if (pending == null || step < 0) {
            return null;
        }
        store.updateIf(DocStore.USER, username, new Rec(), Rec.of("mfa", Rec.of("secret", pending, "enabled", true, "since", Platform.now(), "lastStep", step)));
        return newRecoveryCodes(username);
    }

    /** Turns the second step off; the user shows a current code to do so. */
    public boolean mfaDisable(String username, String code) {
        Rec user = store.get(DocStore.USER, username);
        if (user == null || !Boolean.TRUE.equals(user.at("mfa.enabled"))
                || Crypto.totpStep(Ops.str(user.at("mfa.secret")), code == null ? null : code.trim(), System.currentTimeMillis() / 1000) < 0) {
            return false;
        }
        user.remove("mfa");
        store.save(DocStore.USER, user);
        return true;
    }

    /** The hash kept of an API key's secret; the secret itself is random and long, so a plain digest is enough. */
    public static String hashKey(String secret) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The principal of a system calling with its key ("orv_&lt;name&gt;_&lt;secret&gt;"): the key's roles, no scope, and a
     * user name "key:&lt;name&gt;" so that what it did is attributed to it.
     *
     * @return the principal, or null when the key is unknown, disabled or wrong
     */
    public Principal authenticateApiKey(String key) {
        if (key == null || !key.startsWith("orv_")) {
            return null;
        }
        int cut = key.lastIndexOf('_');
        if (cut <= 4) {
            return null;
        }
        String id = key.substring(4, cut);
        Rec stored = store.get(DocStore.API_KEY, id);
        if (stored == null || !"ACTIVE".equals(stored.str("status")) || stored.str("keyHash") == null) {
            return null;
        }
        byte[] given = hashKey(key.substring(cut + 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(given, stored.str("keyHash").getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return null;
        }
        // when the key was last used, to the minute, so a busy system does not write on every call
        if (stored.str("lastUsedAt") == null || !stored.str("lastUsedAt").startsWith(Platform.now().substring(0, 16))) {
            store.updateIf(DocStore.API_KEY, id, new Rec(), Rec.of("lastUsedAt", Platform.now()));
        }
        return principal(Rec.of("id", "key:" + id, "displayName", stored.str("description"), "roles", stored.get("roles")));
    }

    /** Ends every session of the user: all tokens issued so far stop working. */
    public void revokeTokens(String username) {
        Rec user = store.get(DocStore.USER, username);
        if (user != null) {
            store.updateIf(DocStore.USER, username, new Rec(), Rec.of("tokenVersion", tokenVersion(user) + 1));
        }
    }

    /** The user name locked by the most recent failed sign-in, read once; for the security log. */
    public String takeLastLocked() {
        String name = lastLocked;
        lastLocked = null;
        return name;
    }

    private Principal principal(Rec user) {
        Set<String> roles = new TreeSet<>();
        Set<String> permissions = new TreeSet<>();
        java.util.Map<String, java.math.BigDecimal> limits = new java.util.TreeMap<>();
        boolean unlimited = false;
        for (Object r : Ops.list(user.get("roles"))) {
            Rec role = store.get(DocStore.ROLE, Ops.str(r));
            if (role != null) {
                roles.add(role.str("id"));
                for (Object p : Ops.list(role.get("permissions"))) {
                    permissions.add(Ops.str(p));
                }
                // the approval limits of the roles: the highest per currency; a role without limits lifts them all
                if (role.get("approvalLimits") instanceof java.util.Map<?, ?> m && !m.isEmpty()) {
                    m.forEach((k, v) -> limits.merge(String.valueOf(k), Ops.num(v), java.math.BigDecimal::max));
                } else if (Ops.list(role.get("permissions")).contains("payments.approve") || Ops.list(role.get("permissions")).contains("*")) {
                    unlimited = true;
                }
            }
        }
        return new Principal(user.str("id"), user.str("displayName"), roles, permissions, Scope.of(user.get("scope")), unlimited ? java.util.Map.of() : limits);
    }
}
