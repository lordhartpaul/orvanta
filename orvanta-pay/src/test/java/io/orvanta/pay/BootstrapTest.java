package io.orvanta.pay;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.kernel.Platform;
import io.orvanta.pay.security.AuthService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first user of an installation without a seed file, as in a container. */
class BootstrapTest {

    private static final Path NO_SEED_FILE = Path.of("no-such-directory", "seed-users.yaml");

    private static AuthService auth(DocStore store, String bootstrapPassword) throws Exception {
        Rec security = Rec.of("jwtSecret", "a-test-secret-that-is-long-enough", "bootstrapPassword", bootstrapPassword);
        AuthService auth = new AuthService(new Platform(Config.of(Rec.of("security", security)), store, new MemoryBus()));
        auth.seed(NO_SEED_FILE);
        return auth;
    }

    @Test
    void theBootstrapPasswordCreatesTheAdministratorOnce() throws Exception {
        DocStore store = new MemoryDocStore();
        AuthService auth = auth(store, "first-start-password");
        Rec session = auth.login("admin", "first-start-password");
        assertNotNull(session);
        assertTrue(auth.authenticate(session.str("token")).can("anything.at.all"), "the first user is an administrator");
        assertNull(auth.login("admin", "wrong-password-entirely"));

        // a later start with another value changes nothing: users exist now
        AuthService later = auth(store, "a-different-password");
        assertNull(later.login("admin", "a-different-password"));
        assertNotNull(later.login("admin", "first-start-password"));
        assertEquals(1, store.count(DocStore.USER, null));
    }

    @Test
    void aShortOrMissingBootstrapPasswordCreatesNoUser() throws Exception {
        DocStore weak = new MemoryDocStore();
        auth(weak, "short");
        assertEquals(0, weak.count(DocStore.USER, null));

        DocStore none = new MemoryDocStore();
        auth(none, null);
        assertEquals(0, none.count(DocStore.USER, null));
    }
}
