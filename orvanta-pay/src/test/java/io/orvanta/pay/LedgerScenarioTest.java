package io.orvanta.pay;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.Platform;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.security.Crypto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform's own ledger as the account system: the connectors for account lookup, posting and
 * reversal point at /ledger instead of the simulator, and payments move real balances.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerScenarioTest {

    private static final Path WORKSPACE = Path.of("..", "workspace").toAbsolutePath().normalize();
    private static final String CHANNEL_KEY = "ledger-channel-key-0123456789";
    private static final Map<String, String> PASSWORDS = Map.of(
            "maker1", "ledger-Maker-pass-01", "checker1", "ledger-Checker-pass-02",
            "operator1", "ledger-Operator-pass-03", "viewer1", "ledger-Viewer-pass-04");
    private static final String CUSTOMER = "4070000001";
    private static final String NOSTRO = "9100000001";
    private static final String SUSPENSE = "9900000001";

    @TempDir
    static Path data;
    static OrvantaServer server;
    static DocStore store;
    static String base;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, String> TOKENS = new HashMap<>();

    record Reply(int status, Rec body) {
    }

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://localhost:" + port;
        System.setProperty("ORVANTA_SIM_URL", base + "/sim");
        // the account system of this test is the platform's own ledger
        System.setProperty("ORVANTA_ACCOUNTS_URL", base + "/ledger");
        System.setProperty("ORVANTA_CORPORATE_API_KEY", CHANNEL_KEY);
        store = new MemoryDocStore();
        server = new OrvantaServer(Config.of(Rec.of(
                "units", "all",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "a-scenario-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml",
                        "loginAttempts", "10000"),
                "workspace", Rec.of("dir", WORKSPACE.toString()),
                "data", Rec.of("dir", data.toString()),
                "recovery", Rec.of("stuckSeconds", "2", "intervalSeconds", "1", "retryBaseSeconds", "1", "maxRetries", "1"),
                "simulator", Rec.of("enabled", "true"))), store, new MemoryBus());
        server.start();
        user("maker1", List.of("DESIGNER", "USER_ADMIN", "OPERATOR"));
        user("checker1", List.of("APPROVER"));
        user("operator1", List.of("OPERATOR"));
        user("viewer1", List.of());
    }

    @AfterAll
    static void stop() {
        io.orvanta.core.expr.Time.setGlobal(null);
        server.stop();
        System.clearProperty("ORVANTA_SIM_URL");
        System.clearProperty("ORVANTA_ACCOUNTS_URL");
        System.clearProperty("ORVANTA_CORPORATE_API_KEY");
    }

    private static void user(String name, List<String> roles) {
        store.save(DocStore.USER, Rec.of("id", name, "displayName", name, "roles", roles, "status", "ACTIVE",
                "passwordHash", Crypto.hashPassword(PASSWORDS.get(name))));
    }

    // ---- driving the API ----

    private static Reply send(String method, String path, String user, String contentType, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType);
        if (user != null) {
            b.header("Authorization", "Bearer " + token(user));
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = HTTP.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        Object parsed = r.body().isBlank() ? new Rec() : Json.parseAny(r.body());
        return new Reply(r.statusCode(), parsed instanceof Rec rec ? rec : Rec.of("items", parsed));
    }

    private static Reply get(String path, String user) throws Exception {
        return send("GET", path, user, "application/json", null);
    }

    private static Reply post(String path, String user, Rec body) throws Exception {
        return send("POST", path, user, "application/json", body == null ? null : Json.write(body));
    }

    private static String token(String user) throws Exception {
        String cached = TOKENS.get(user);
        if (cached == null) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Rec.of("username", user, "password", PASSWORDS.get(user))))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), "sign-in of " + user);
            cached = Json.parse(r.body()).str("token");
            TOKENS.put(user, cached);
        }
        return cached;
    }

    private static <T> T await(String what, Supplier<T> attempt) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            T value = attempt.get();
            if (value != null && !Boolean.FALSE.equals(value)) {
                return value;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static Rec quietGet(String path, String user) {
        try {
            return get(path, user).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uploads a file as operator1 and returns the id of the stored message. */
    private static String upload(String raw, String fileName) throws Exception {
        Reply r = send("POST", "/api/inbound?fileName=" + fileName, "operator1", "text/plain", raw);
        assertEquals(200, r.status(), r.body().toString());
        return ((Rec) Ops.list(r.body().get("messages")).get(0)).str("id");
    }

    private static Map<String, Rec> transactionsOf(String instructionId) {
        Map<String, Rec> byE2e = new HashMap<>();
        for (Object t : Ops.list(quietGet("/api/transactions?instructionId=" + instructionId, "operator1").get("items"))) {
            byE2e.put(((Rec) t).str("endToEndId"), (Rec) t);
        }
        return byE2e;
    }

    private static String status(String txnId) {
        return quietGet("/api/transactions/" + txnId, "operator1").str("status");
    }

    private static String onePayment(String msgId, String creditorName, String currency, String creditorBic, String debtorAccount) throws Exception {
        String raw = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>" + msgId
                + "</MsgId><CreDtTm>2026-10-04T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>" + msgId + "-1</PmtInfId>"
                + "<ReqdExctnDt><Dt>2026-10-05</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>" + debtorAccount + "</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>" + msgId + "</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"" + currency + "\">900.00</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>" + creditorBic + "</BICFI></FinInstnId></CdtrAgt>"
                + "<Cdtr><Nm>" + creditorName + "</Nm></Cdtr><CdtrAcct><Id><Othr><Id>62011223344</Id></Othr></Id></CdtrAcct>"
                + "</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
        String instruction = upload(raw, msgId + ".xml");
        return await("the transaction of " + msgId, () -> transactionsOf(instruction).get(msgId)).str("id");
    }

    /** One user requests, another approves; returns the decided approval. */
    private static Rec approve(Reply request) throws Exception {
        assertEquals(200, request.status(), request.body().toString());
        assertEquals("PENDING", request.body().str("status"));
        Reply decided = post("/api/approvals/" + request.body().str("id") + "/approve", "checker1", Rec.of("comment", "checked"));
        assertEquals(200, decided.status(), decided.body().toString());
        assertEquals("APPROVED", decided.body().str("status"), String.valueOf(decided.body().get("failure")));
        return decided.body();
    }


    private static java.math.BigDecimal balance(String account) {
        return Ops.num(quietGet("/api/ledger/accounts/" + account, "operator1").get("balance"));
    }

    private static void assertBalance(String expected, String account) {
        assertEquals(0, new java.math.BigDecimal(expected).compareTo(balance(account)), account + " has " + balance(account) + ", expected " + expected);
    }

    private static Rec open(String id, String name, String type, String currency) throws Exception {
        return approve(post("/api/ledger/accounts", "operator1", Rec.of("id", id, "name", name, "type", type, "currency", currency)));
    }

    @Test
    @Order(1)
    void accountsAreOpenedAndFundedOnlyWithASecondPerson() throws Exception {
        assertEquals(403, post("/api/ledger/accounts", "viewer1", Rec.of("id", CUSTOMER, "name", "x", "type", "CUSTOMER", "currency", "ZAR")).status());
        assertEquals(422, post("/api/ledger/accounts", "operator1", Rec.of("id", CUSTOMER, "name", "x", "type", "SAVINGS", "currency", "ZAR")).status());
        assertEquals(422, post("/api/ledger/accounts", "operator1", Rec.of("id", "1", "name", "x", "type", "CUSTOMER", "currency", "ZAR")).status());
        Reply asked = post("/api/ledger/accounts", "operator1", Rec.of("id", CUSTOMER, "name", "Karoo Mining Supplies", "type", "CUSTOMER", "currency", "ZAR"));
        assertEquals(404, get("/api/ledger/accounts/" + CUSTOMER, "operator1").status(), "not there before the approval");
        // the person who asked cannot approve
        assertTrue(post("/api/approvals/" + asked.body().str("id") + "/approve", "operator1", Rec.of("comment", "me again")).status() >= 400);
        approve(asked);
        open(NOSTRO, "Reserve Bank settlement account", "NOSTRO", "ZAR");
        open(SUSPENSE, "Cash paid in at the counter", "SUSPENSE", "ZAR");
        open("4070000002", "Euro account", "CUSTOMER", "EUR");
        assertEquals("ACTIVE", get("/ledger/accounts/" + CUSTOMER, null).body().str("status"));
        assertEquals("UNKNOWN", get("/ledger/accounts/4079999999", null).body().str("status"));
        assertBalance("0", CUSTOMER);

        // money comes onto the account with a posting that a second person approves
        assertEquals(422, post("/api/ledger/postings", "operator1", Rec.of("debitAccount", SUSPENSE, "creditAccount", "4079999999", "amount", 10, "currency", "ZAR", "text", "x")).status());
        assertEquals(422, post("/api/ledger/postings", "operator1", Rec.of("debitAccount", SUSPENSE, "creditAccount", CUSTOMER, "amount", 10, "currency", "ZAR")).status(), "a text is needed");
        approve(post("/api/ledger/postings", "operator1", Rec.of("debitAccount", SUSPENSE, "creditAccount", CUSTOMER, "amount", new java.math.BigDecimal("1000.00"),
                "currency", "ZAR", "text", "Cash paid in")));
        assertBalance("1000", CUSTOMER);
        assertBalance("-1000", SUSPENSE);
        // a customer cannot be debited for more than there is; the approval fails and says why
        Reply tooMuch = post("/api/ledger/postings", "operator1", Rec.of("debitAccount", CUSTOMER, "creditAccount", SUSPENSE, "amount", 5000, "currency", "ZAR", "text", "Cash paid out"));
        Reply decided = post("/api/approvals/" + tooMuch.body().str("id") + "/approve", "checker1", Rec.of("comment", "checked"));
        assertTrue(decided.body().toString().contains("INSUFFICIENT_FUNDS"), decided.body().toString());
        assertBalance("1000", CUSTOMER);
    }

    @Test
    @Order(2)
    void aPaymentMovesTheBalanceAndComesBackWhenItIsReturned() throws Exception {
        String paid = onePayment("LEDGER-PAY-1", "Normal Supplier", "ZAR", "FIRNZAJJ", CUSTOMER);
        Rec posted = await("the payment to be posted", () -> {
            Rec x = quietGet("/api/transactions/" + paid, "operator1");
            return "POSTED".equals(x.str("posting.status")) ? x : null;
        });
        assertEquals("LP-" + paid + "-DEBIT", posted.str("posting.postingId"));
        assertBalance("100", CUSTOMER);
        assertBalance("900", NOSTRO);

        // the same customer has not enough for a second payment of 900: nothing is booked, the payment is rejected
        String refused = onePayment("LEDGER-PAY-2", "Normal Supplier", "ZAR", "FIRNZAJJ", CUSTOMER);
        Rec rejected = await("the second payment to be rejected", () -> {
            Rec x = quietGet("/api/transactions/" + refused, "operator1");
            return String.valueOf(x.str("status")).startsWith("REJECTED") ? x : null;
        });
        assertEquals("AM04", rejected.str("reasonCode"));
        assertEquals("INSUFFICIENT_FUNDS", rejected.str("posting.reason"));
        assertBalance("100", CUSTOMER);

        // an account the ledger does not know is no account
        String unknown = onePayment("LEDGER-PAY-3", "Normal Supplier", "ZAR", "FIRNZAJJ", "4079999999");
        await("the payment from an unknown account to be rejected", () -> String.valueOf(status(unknown)).startsWith("REJECTED"));

        // the receiving bank sends the first payment back: the posting is reversed and the money is on the account again
        await("the payment to be accepted", () -> "ACCEPTED".equals(status(paid)));
        server.simulator.returnFunds(paid, "AC04", "Closed account number");
        await("the posting to be reversed", () -> "REVERSED".equals(quietGet("/api/transactions/" + paid, "operator1").str("posting.status")));
        assertBalance("1000", CUSTOMER);
        assertBalance("0", NOSTRO);

        // what the account page shows: newest first, each entry with the balance after it
        Rec account = get("/api/ledger/accounts/" + CUSTOMER, "operator1").body();
        List<?> entries = Ops.list(account.get("entries"));
        assertEquals(3, entries.size(), entries.toString());
        Rec last = (Rec) entries.get(0);
        assertEquals("CRDT", last.str("creditDebit"));
        assertEquals("LP-" + paid + "-DEBIT", last.str("reversalOf"));
        assertEquals(NOSTRO, last.str("otherAccount"));
        assertEquals(0, new java.math.BigDecimal("1000").compareTo(Ops.num(last.get("balance"))));
        assertEquals(0, new java.math.BigDecimal("100").compareTo(Ops.num(((Rec) entries.get(1)).get("balance"))));
        Rec listed = get("/api/ledger/accounts?type=CUSTOMER&q=Karoo", "operator1").body();
        assertEquals(1, Ops.list(listed.get("items")).size());
        assertEquals(0, new java.math.BigDecimal("1000").compareTo(Ops.num(((Rec) Ops.list(listed.get("items")).get(0)).get("balance"))));
    }

    @Test
    @Order(4)
    void aPostingIsMadeOnceAndRefusedWhenItCannotBeMade() throws Exception {
        Rec request = Rec.of("idempotencyKey", "KEY-1", "reference", "TEST", "debitAccount", CUSTOMER, "creditAccount", NOSTRO, "amount", new java.math.BigDecimal("250.00"), "currency", "ZAR");
        Rec first = post("/ledger/postings", null, request).body();
        Rec again = post("/ledger/postings", null, request).body();
        assertEquals("POSTED", first.str("status"));
        assertEquals(first.str("postingId"), again.str("postingId"));
        assertBalance("750", CUSTOMER);

        assertEquals("CURRENCY_MISMATCH", post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-2", "debitAccount", CUSTOMER, "creditAccount", "4070000002",
                "amount", 5, "currency", "ZAR")).body().str("reason"));
        assertEquals("UNKNOWN_ACCOUNT", post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-3", "debitAccount", CUSTOMER, "creditAccount", "4079999999",
                "amount", 5, "currency", "ZAR")).body().str("reason"));
        assertEquals(422, post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-4", "debitAccount", CUSTOMER, "creditAccount", NOSTRO, "amount", 0, "currency", "ZAR")).status());

        // reversed once: the same request again changes nothing, another request for the same posting is refused
        Rec reversal = Rec.of("idempotencyKey", "KEY-1-R", "postingId", first.str("postingId"), "reason", "test");
        assertEquals("REVERSED", post("/ledger/postings/reverse", null, reversal).body().str("status"));
        assertEquals("REVERSED", post("/ledger/postings/reverse", null, reversal).body().str("status"));
        assertBalance("1000", CUSTOMER);
        assertEquals(422, post("/ledger/postings/reverse", null, Rec.of("idempotencyKey", "KEY-1-R2", "postingId", first.str("postingId"))).status());
        assertEquals(422, post("/ledger/postings/reverse", null, Rec.of("idempotencyKey", "KEY-9-R", "postingId", "LP-NONE")).status());

        // a blocked account is not debited, and an account with money on it is not closed
        approve(post("/api/ledger/accounts", "operator1", Rec.of("id", CUSTOMER, "status", "BLOCKED")));
        assertEquals("BLOCKED", get("/ledger/accounts/" + CUSTOMER, null).body().str("status"));
        assertEquals("ACCOUNT_BLOCKED", post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-5", "debitAccount", CUSTOMER, "creditAccount", NOSTRO,
                "amount", 5, "currency", "ZAR")).body().str("reason"));
        Reply close = post("/api/ledger/accounts", "operator1", Rec.of("id", CUSTOMER, "status", "CLOSED"));
        Reply decided = post("/api/approvals/" + close.body().str("id") + "/approve", "checker1", Rec.of("comment", "checked"));
        assertTrue(decided.body().toString().contains("cannot be closed"), decided.body().toString());
        approve(post("/api/ledger/accounts", "operator1", Rec.of("id", CUSTOMER, "status", "ACTIVE", "overdraftLimit", 500)));
        // with an overdraft limit of 500 the customer can go to minus 500, not further
        assertEquals("POSTED", post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-6", "debitAccount", CUSTOMER, "creditAccount", NOSTRO,
                "amount", 1400, "currency", "ZAR")).body().str("status"));
        assertEquals("INSUFFICIENT_FUNDS", post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-7", "debitAccount", CUSTOMER, "creditAccount", NOSTRO,
                "amount", 200, "currency", "ZAR")).body().str("reason"));
        assertBalance("-400", CUSTOMER);
    }

    @Test
    @Order(3)
    void aConvertedPaymentGoesThroughThePositionAccountsAndEveryCurrencyBalances() throws Exception {
        // a posting between two currencies is refused while the bank has no position accounts for them
        open("9100000002", "Correspondent bank account", "NOSTRO", "USD");
        Rec request = Rec.of("idempotencyKey", "FX-1", "debitAccount", SUSPENSE, "creditAccount", "9100000002", "amount", new java.math.BigDecimal("1850.00"),
                "currency", "ZAR", "creditAmount", new java.math.BigDecimal("100.00"), "creditCurrency", "USD");
        assertEquals("NO_POSITION_ACCOUNT", post("/ledger/postings", null, request).body().str("reason"));
        open("9800000001", "Position in rand", "POSITION", "ZAR");
        open("9800000002", "Position in dollars", "POSITION", "USD");
        assertEquals("CURRENCY_MISMATCH", post("/ledger/postings", null, Rec.of("idempotencyKey", "FX-0", "debitAccount", SUSPENSE, "creditAccount", "9100000002",
                "amount", 1850, "currency", "ZAR")).body().str("reason"), "the amount in the other currency has to be named");

        java.math.BigDecimal suspenseBefore = balance(SUSPENSE);
        Rec posted = post("/ledger/postings", null, request).body();
        assertEquals("POSTED", posted.str("status"), posted.toString());
        assertEquals(0, new java.math.BigDecimal("100").compareTo(Ops.num(posted.get("creditAmount"))));
        // four sides, and each currency adds up to nothing by itself
        assertEquals(0, suspenseBefore.subtract(new java.math.BigDecimal("1850")).compareTo(balance(SUSPENSE)));
        assertBalance("1850", "9800000001");
        assertBalance("-100", "9800000002");
        assertBalance("100", "9100000002");
        Rec nostro = get("/api/ledger/accounts/9100000002", "operator1").body();
        Rec line = (Rec) Ops.list(nostro.get("entries")).get(0);
        assertEquals("CRDT", line.str("creditDebit"));
        assertEquals("USD", line.str("currency"));
        assertEquals("9800000002", line.str("otherAccount"), "the dollars came from the dollar position");

        // taken back: all four sides the other way round
        assertEquals("REVERSED", post("/ledger/postings/reverse", null, Rec.of("idempotencyKey", "FX-1-R", "postingId", posted.str("postingId"))).body().str("status"));
        assertEquals(0, suspenseBefore.compareTo(balance(SUSPENSE)));
        assertBalance("0", "9800000001");
        assertBalance("0", "9800000002");
        assertBalance("0", "9100000002");

        // a real payment: a rand customer pays 100 dollars abroad at the quoted rate of 18.50
        open("4070000003", "Karoo Mining Supplies, second account", "CUSTOMER", "ZAR");
        // the SWIFT route charges a fee from reference.FeeSchedule (150 plus 0.1 percent of 1850 rand): it needs the fee account of the schedule
        open("4099000001", "Payment fees ZAR", "FEE", "ZAR");
        approve(post("/api/ledger/postings", "operator1", Rec.of("debitAccount", SUSPENSE, "creditAccount", "4070000003", "amount", 20000, "currency", "ZAR", "text", "Cash paid in")));
        String raw = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>LEDGER-FX-1</MsgId>"
                + "<CreDtTm>2026-10-04T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>LEDGER-FX-1-1</PmtInfId>"
                + "<ReqdExctnDt><Dt>2026-10-05</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>4070000003</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>LEDGER-FX-1</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"USD\">100.00</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>CHASUS33</BICFI></FinInstnId></CdtrAgt>"
                + "<Cdtr><Nm>Harbor Tools Inc</Nm></Cdtr><CdtrAcct><Id><Othr><Id>62011223344</Id></Othr></Id></CdtrAcct>"
                + "</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
        String instruction = upload(raw, "ledger-fx.xml");
        String paid = await("the transaction", () -> transactionsOf(instruction).get("LEDGER-FX-1")).str("id");
        Rec booked = await("the converted payment to be posted", () -> {
            Rec x = quietGet("/api/transactions/" + paid, "operator1");
            return "POSTED".equals(x.str("posting.status")) || String.valueOf(x.str("status")).startsWith("REJECTED") || "REPAIR".equals(x.str("status")) ? x : null;
        });
        assertEquals("POSTED", booked.str("posting.status"), booked.toString());
        assertBalance("17998.15", "4070000003");
        assertBalance("151.85", "4099000001");
        assertBalance("1850", "9800000001");
        assertBalance("-100", "9800000002");
        assertBalance("100", "9100000002");
    }

    @Test
    @Order(6)
    void aFeeIsBookedAndReturnedWithThePostingAndTheDayIsExported() throws Exception {
        String fees = "4099000009";
        String customer = "4070000009";
        open(customer, "Fee paying customer", "CUSTOMER", "ZAR");
        approve(post("/api/ledger/postings", "operator1", Rec.of("debitAccount", SUSPENSE, "creditAccount", customer, "amount", 500, "currency", "ZAR", "text", "Cash paid in")));
        // no fee account yet: the posting with a fee is refused, nothing is booked
        Rec request = Rec.of("idempotencyKey", "FEE-1", "reference", "FEE-TEST", "debitAccount", customer, "creditAccount", NOSTRO, "amount", 100, "currency", "ZAR",
                "feeAmount", new java.math.BigDecimal("7.50"), "feeAccount", fees, "feeText", "Fee for a payment");
        assertEquals("NO_FEE_ACCOUNT", post("/ledger/postings", null, request).body().str("reason"));
        assertBalance("500", customer);
        open(fees, "Payment fees ZAR", "FEE", "ZAR");
        Rec posted = post("/ledger/postings", null, request).body();
        assertEquals("POSTED", posted.str("status"), posted.toString());
        assertEquals("LP-FEE-1-FEE", posted.str("feeEntry"));
        assertBalance("392.50", customer);
        assertBalance("7.50", fees);
        // the fee counts towards the funds check: 392.50 on the account, 390 plus 7.50 is too much
        assertEquals("INSUFFICIENT_FUNDS", post("/ledger/postings", null, Rec.of("idempotencyKey", "FEE-2", "debitAccount", customer, "creditAccount", NOSTRO, "amount", 390, "currency", "ZAR",
                "feeAmount", 7.5, "feeAccount", fees)).body().str("reason"));
        // the fee is an entry of its own on the account, marked as the fee of the posting
        Rec page = get("/api/ledger/accounts/" + customer, "operator1").body();
        List<Rec> entries = Ops.list(page.get("entries")).stream().map(e -> (Rec) e).toList();
        assertTrue(entries.stream().anyMatch(e -> "LP-FEE-1".equals(e.str("feeOf")) && "DBIT".equals(e.str("creditDebit"))), entries.toString());
        // reversed: the fee goes back too
        assertEquals("REVERSED", post("/ledger/postings/reverse", null, Rec.of("idempotencyKey", "FEE-1-R", "postingId", "LP-FEE-1", "reason", "returned")).body().str("status"));
        assertBalance("500", customer);
        assertBalance("0", fees);

        // a value date ahead: the balance by value date does not count it yet
        String tomorrow = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertEquals(422, post("/ledger/postings", null, Rec.of("idempotencyKey", "VAL-0", "debitAccount", customer, "creditAccount", NOSTRO, "amount", 10, "currency", "ZAR", "valueDate", "next week")).status());
        Rec valued = post("/ledger/postings", null, Rec.of("idempotencyKey", "VAL-1", "debitAccount", customer, "creditAccount", NOSTRO, "amount", 60, "currency", "ZAR", "valueDate", tomorrow)).body();
        assertEquals(tomorrow, valued.str("valueDate"));
        assertBalance("440", customer);
        Rec byValue = get("/api/ledger/accounts/" + customer + "?valueDate=" + today, "operator1").body();
        assertEquals(0, new java.math.BigDecimal("500").compareTo(Ops.num(byValue.get("valueBalance"))), byValue.toString());
        assertEquals(0, new java.math.BigDecimal("440").compareTo(Ops.num(get("/api/ledger/accounts/" + customer + "?valueDate=" + tomorrow, "operator1").body().get("valueBalance"))));

        // the day's journal as a file: every entry of today, the fee and the value date among them
        HttpResponse<String> csv = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/ledger/days/" + today + "/entries.csv"))
                .header("Authorization", "Bearer " + token("operator1")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csv.statusCode());
        assertTrue(csv.body().startsWith("id,bookedAt,bookDate,valueDate,"), csv.body().substring(0, 60));
        assertTrue(csv.body().contains("LP-FEE-1-FEE,") && csv.body().contains("," + tomorrow + "," + customer + "," + NOSTRO + ",60,ZAR"), csv.body());
        assertEquals(422, send("GET", "/api/ledger/days/yesterday/entries.csv", "operator1", "text/csv", null).status());
    }

    @Test
    @Order(5)
    void aDayIsClosedAndBalancesGoOnFromThere() throws Exception {
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertEquals(422, post("/api/ledger/close-day", "operator1", Rec.of("date", today)).status(), "today is not over");
        assertEquals(403, post("/api/ledger/close-day", "viewer1", Rec.of("date", today)).status());
        try {
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.now().plus(java.time.Duration.ofDays(1)));
            Rec closed = post("/api/ledger/close-day", "operator1", Rec.of("date", today)).body();
            assertEquals(9, Ops.num(closed.get("accounts")).intValue(), closed.toString());
            assertEquals(8, Ops.num(closed.get("accountsWithEntries")).intValue(), "every account but the euro account moved (the fee account took the SWIFT fee): " + closed);
            assertEquals(0, Ops.num(post("/api/ledger/close-day", "operator1", Rec.of("date", today)).body().get("accounts")).intValue(), "a closed day stays as it is");
            Rec day = store.get(io.orvanta.pay.engine.Ledger.BALANCE, CUSTOMER + "|" + today);
            assertEquals(0, java.math.BigDecimal.ZERO.compareTo(Ops.num(day.get("opening"))));
            assertEquals(0, new java.math.BigDecimal("-400").compareTo(Ops.num(day.get("closing"))));
            assertBalance("-400", CUSTOMER);
            // the next day's entry is added to the closed balance
            assertEquals("POSTED", post("/ledger/postings", null, Rec.of("idempotencyKey", "KEY-8", "debitAccount", SUSPENSE, "creditAccount", CUSTOMER,
                    "amount", 650, "currency", "ZAR")).body().str("status"));
            assertBalance("250", CUSTOMER);

            // ---- the statement of the closed day: balances from the ledger, entries with the payment they were for ----
            assertEquals(422, post("/api/account-statements", "operator1", Rec.of("account", "4079999999", "date", today)).status(), "no ledger account, no statement");
            assertEquals(403, post("/api/account-statements", "viewer1", Rec.of("account", CUSTOMER, "date", today)).status());
            Reply asked = post("/api/account-statements", "operator1", Rec.of("account", CUSTOMER, "date", today));
            assertEquals(200, asked.status(), asked.body().toString());
            String id = asked.body().str("id");
            assertEquals(id, post("/api/account-statements", "operator1", Rec.of("account", CUSTOMER, "date", today)).body().str("id"), "one statement per account and day");
            Rec statement = await("the statement to be sent", () -> {
                Rec o = quietGet("/api/outbound/" + id, "operator1");
                return "SENT".equals(o.str("status")) ? o : null;
            });
            assertEquals("accountStatement", statement.str("kind"));
            String camt053 = statement.str("payload").replaceAll(">\\s+<", "><");
            assertTrue(camt053.contains("camt.053.001.08") && camt053.contains("<Othr><Id>" + CUSTOMER + "</Id></Othr>"), camt053);
            assertTrue(camt053.contains("<Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"ZAR\">0</Amt><CdtDbtInd>CRDT</CdtDbtInd>")
                    || camt053.contains("<Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"ZAR\">0.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>"), camt053);
            assertTrue(camt053.matches("(?s).*<Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"ZAR\">400(\\.0+)?</Amt><CdtDbtInd>DBIT</CdtDbtInd>.*"), camt053);
            assertTrue(camt053.contains("<EndToEndId>LEDGER-PAY-1</EndToEndId>") && camt053.contains("<RvslInd>true</RvslInd>") && camt053.contains("<AddtlNtryInf>Cash paid in</AddtlNtryInf>"), camt053);
            assertTrue(!camt053.contains("LEDGER-PAY-2"), "a payment that was refused has no entry");
            assertEquals(Ops.num(statement.get("entryCount")).intValue(), camt053.split("<Ntry>").length - 1);
            assertEquals(0, new java.math.BigDecimal("-400").compareTo(Ops.num(statement.get("closing"))));

            // ---- the same day as an MT940 for a customer who works with MT: another file, checked against the field rules ----
            Reply mtAsked = post("/api/account-statements", "operator1", Rec.of("account", CUSTOMER, "date", today, "channel", "channels.CustomerMtStatementOutbound"));
            assertEquals(200, mtAsked.status(), mtAsked.body().toString());
            assertEquals(id + "M", mtAsked.body().str("id"));
            Rec mt = await("the MT940 to be sent", () -> {
                Rec o = quietGet("/api/outbound/" + id + "M", "operator1");
                return "SENT".equals(o.str("status")) ? o : null;
            });
            String mt940 = mt.str("payload").replace("\r", "");
            String yymmdd = today.substring(2).replace("-", "");
            assertTrue(mt940.contains("{2:I940KAROZAJJ") && mt940.contains(":25:" + CUSTOMER) && mt940.contains(":60F:C" + yymmdd + "ZAR0,")
                    && mt940.contains(":62F:D" + yymmdd + "ZAR400,"), mt940);
            assertEquals(Ops.num(statement.get("entryCount")).intValue(), mt940.split("\n:61:").length - 1);
            assertTrue(mt940.contains("NTRFLEDGER-PAY-1//") && mt940.contains(":61:" + yymmdd + "RD900,") && mt940.contains(":86:Cash paid in"), mt940);
            assertEquals(List.of(), Ops.list(((Rec) Ops.list(post("/api/studio/check-message", "maker1", Rec.of("raw", mt.str("payload"))).body().get("messages")).get(0)).get("problems")));
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
        }
    }
}
