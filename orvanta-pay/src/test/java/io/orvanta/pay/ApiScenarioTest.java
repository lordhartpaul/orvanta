package io.orvanta.pay;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.Platform;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.api.ApiServer;
import io.orvanta.pay.engine.Lifecycle;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Use cases driven the way users and systems drive them: over HTTP, signed in with a role, with
 * every change going through request and approval on the real endpoints. The whole platform runs
 * in this process (all services, the API, the simulated external systems) on the in-memory store.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiScenarioTest {

    private static final Path WORKSPACE = Path.of("..", "workspace").toAbsolutePath().normalize();
    private static final String CHANNEL_KEY = "scenario-channel-key-0123456789";
    private static final Map<String, String> PASSWORDS = Map.of(
            "maker1", "scenario-Maker-pass-01", "checker1", "scenario-Checker-pass-02",
            "operator1", "scenario-Operator-pass-03", "viewer1", "scenario-Viewer-pass-04");

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
                "accountReports", Rec.of("checkSeconds", "1"),
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
        return onePayment(msgId, creditorName, currency, creditorBic, debtorAccount, "900.00");
    }

    private static String onePayment(String msgId, String creditorName, String currency, String creditorBic, String debtorAccount, String amount) throws Exception {
        String raw = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>" + msgId
                + "</MsgId><CreDtTm>2026-10-04T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>" + msgId + "-1</PmtInfId>"
                + "<ReqdExctnDt><Dt>2026-10-05</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>" + debtorAccount + "</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>" + msgId + "</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"" + currency + "\">" + amount + "</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>" + creditorBic + "</BICFI></FinInstnId></CdtrAgt>"
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

    // ---- use cases ----

    @Test
    @Order(1)
    void eachRoleReachesOnlyWhatItsPermissionsAllow() throws Exception {
        // path, then the expected status for operator1, maker1, checker1 and viewer1 (no role at all)
        Object[][] matrix = {
                {"/api/me", 200, 200, 200, 200},
                {"/api/dashboard", 200, 200, 200, 403},
                {"/api/transactions", 200, 200, 200, 403},
                {"/api/statements", 200, 200, 200, 403},
                {"/api/deadletters", 200, 200, 403, 403},
                {"/api/studio/models", 403, 200, 200, 403},
                {"/api/studio/deployments", 403, 200, 200, 403},
                {"/api/users", 403, 200, 200, 403},
                {"/api/security/events", 403, 200, 200, 403},
                {"/api/x/limits/4051122334", 404, 404, 404, 403},
        };
        String[] users = {"operator1", "maker1", "checker1", "viewer1"};
        for (Object[] row : matrix) {
            for (int i = 0; i < users.length; i++) {
                assertEquals(row[i + 1], get((String) row[0], users[i]).status(), users[i] + " GET " + row[0]);
            }
        }
        // changing things: only the role that holds the permission
        assertEquals(403, send("POST", "/api/inbound", "viewer1", "text/plain", "x").status());
        assertEquals(403, send("POST", "/api/inbound", "checker1", "text/plain", "x").status());
        assertEquals(403, post("/api/studio/changes", "operator1", Rec.of("text", "kind: Flow\nname: a.B\n")).status());
        assertEquals(403, post("/api/users", "operator1", Rec.of("username", "someone")).status());
        assertEquals(403, send("PUT", "/api/x/limits/4051122334", "operator1", "application/json", "{\"perTransaction\": 5}").status());
        assertEquals(401, get("/api/transactions", null).status());
    }

    @Test
    @Order(2)
    void anOperatorSubmitsAFileAndFollowsEveryPaymentToItsOutcome() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml"));
        String id = upload(raw, "salaries.xml");
        await("the customer status report", () -> "COMPLETE".equals(quietGet("/api/messages/" + id, "operator1").str("reportState")));

        Map<String, Rec> txns = transactionsOf(id);
        assertEquals("ACCEPTED", txns.get("SAL-0001").str("status"));
        assertEquals("ACCEPTED", txns.get("SAL-0002").str("status"));
        assertEquals("AM01", txns.get("SAL-0003").str("reasonCode"));
        assertEquals("SANC", txns.get("SAL-0004").str("reasonCode"));
        assertEquals("REJECTED_BY_EXTERNAL", txns.get("SAL-0005").str("status"));

        Rec message = get("/api/messages/" + id, "operator1").body();
        assertEquals("DEBULKED", message.str("status"));
        assertTrue(message.str("raw").contains("SALARY-2026-10-001"), "the detail carries the message as received");
        assertFalse(Ops.list(message.get("events")).isEmpty());
        assertFalse(get("/api/messages?purpose=instruction", "operator1").body().toString().contains("<Document"), "lists do not carry payloads");

        Rec detail = get("/api/transactions/" + txns.get("SAL-0001").str("id"), "operator1").body();
        assertEquals("POSTED", detail.str("posting.status"));
        assertTrue(Ops.list(detail.get("events")).size() >= 5, "created, routed, bulked, sent, accepted");
        Rec file = get("/api/outbound/" + detail.str("outboundId"), "operator1").body();
        assertEquals("ACKNOWLEDGED", file.str("status"));
        assertTrue(file.str("payload").contains("pacs.008.001.08"));

        assertEquals(1, Ops.list(get("/api/transactions?instructionId=" + id + "&status=REJECTED_BY_EXTERNAL", "operator1").body().get("items")).size());
        assertEquals(400, get("/api/transactions?limit=abc", "operator1").status());
        assertEquals(404, get("/api/transactions/ORVTXN9999999999", "operator1").status());
        Rec dashboard = get("/api/dashboard", "operator1").body();
        assertTrue(Ops.num(dashboard.at("transactions.ACCEPTED")).intValue() >= 2);

        // the same file again, and a file whose control sum is wrong, are rejected as files
        String again = upload(raw, "again.xml");
        await("the duplicate to be rejected", () -> "REJECTED".equals(quietGet("/api/messages/" + again, "operator1").str("status")));
        assertEquals("AM05", get("/api/messages/" + again, "operator1").body().str("reasonCode"));
        String wrongSum = upload(raw.replace("SALARY-2026-10-001", "SALARY-WRONG-SUM").replace("<CtrlSum>48550.75</CtrlSum>", "<CtrlSum>1.00</CtrlSum>"), "sum.xml");
        await("the mismatch to be rejected", () -> "REJECTED".equals(quietGet("/api/messages/" + wrongSum, "operator1").str("status")));
        assertEquals("IN04", get("/api/messages/" + wrongSum, "operator1").body().str("reasonCode"));
    }

    @Test
    @Order(3)
    void aDesignerChangesARuleAndItTakesEffectOnlyAfterApproval() throws Exception {
        String name = "payments.rules.TransactionValidation";
        Rec model = get("/api/studio/models/" + name, "maker1").body();
        assertTrue(model.str("generated").contains("extends Elements.RuleSet"), "the generated Java is shown");
        String original = model.str("text");

        // a broken edit is reported with the place, and cannot be submitted
        Reply broken = post("/api/studio/validate", "maker1", Rec.of("text", original.replace("txn.amount <= 999999999.99", "txn.amount <=")));
        assertEquals(false, broken.body().get("ok"));
        assertTrue(broken.body().toString().contains("AMOUNT_WITHIN_LIMIT"));
        assertEquals(422, post("/api/studio/changes", "maker1", Rec.of("text", original.replace("txn.amount <= 999999999.99", "txn.amount <="))).status());

        // the edit is tried out before anything is deployed
        String edited = original.replace("txn.amount <= 999999999.99", "txn.amount <= 500");
        Reply trial = post("/api/studio/run", "maker1", Rec.of("text", edited, "target", name,
                "scope", Rec.of("txn", Rec.of("endToEndId", "X", "amount", 900, "currency", "ZAR",
                        "debtor", Rec.of("account", "1"), "creditor", Rec.of("account", "2", "name", "N")))));
        assertTrue(trial.body().at("result.codes").toString().contains("AM02"));

        Reply request = post("/api/studio/changes", "maker1", Rec.of("text", edited, "comment", "limit of 500 for the scenario"));
        String approvalId = request.body().str("id");
        long versionBefore = Ops.num(get("/api/health", null).body().get("version")).longValue();
        assertEquals(403, post("/api/approvals/" + approvalId + "/approve", "maker1", null).status(), "not by the maker");
        assertEquals(403, post("/api/approvals/" + approvalId + "/approve", "operator1", null).status(), "not without the permission");
        String before = onePayment("RULE-BEFORE", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("the payment under the old rule", () -> "ACCEPTED".equals(status(before)));

        Rec approval = get("/api/approvals/" + approvalId, "checker1").body();
        assertEquals(original, approval.str("currentText"), "the checker sees what is deployed next to what is proposed");
        approve(request);
        assertEquals(versionBefore + 1, Ops.num(get("/api/health", null).body().get("version")).longValue());
        assertEquals(409, post("/api/approvals/" + approvalId + "/approve", "checker1", null).status(), "decided once");

        String after = onePayment("RULE-AFTER", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("the payment under the new rule", () -> "REJECTED_BY_APPLICATION".equals(status(after)));
        assertEquals("AM02", get("/api/transactions/" + after, "operator1").body().str("reasonCode"));

        // back to the original, the same way
        approve(post("/api/studio/changes", "maker1", Rec.of("text", original, "comment", "restore")));
        Reply tests = post("/api/studio/tests", "maker1", null);
        assertEquals(0, Ops.num(tests.body().get("failed")).intValue(), tests.body().toString());
        assertTrue(Ops.num(tests.body().get("total")).intValue() >= 60);
    }

    @Test
    @Order(4)
    void aHeldPaymentIsReleasedOnlyThroughRequestAndApproval() throws Exception {
        String txn = onePayment("API-HOLD", "FRAUDCHECK Supplies", "ZAR", "FIRNZAJJ", "4051122334");
        await("the payment to be held", () -> "HELD".equals(status(txn)));
        assertEquals(1, Ops.list(get("/api/transactions?status=HELD", "operator1").body().get("items")).size());
        assertEquals(403, post("/api/transactions/" + txn + "/release", "viewer1", null).status());

        Reply request = post("/api/transactions/" + txn + "/release", "operator1", Rec.of("comment", "known supplier"));
        assertEquals("HELD", status(txn), "a request alone changes nothing");
        assertEquals(403, post("/api/approvals/" + request.body().str("id") + "/approve", "operator1", null).status());
        approve(request);
        await("the released payment to be accepted", () -> "ACCEPTED".equals(status(txn)));
        assertEquals(409, post("/api/transactions/" + txn + "/release", "operator1", null).status(), "not held any more");
    }

    @Test
    @Order(5)
    void anAcceptedPaymentIsCancelledThroughApprovalAndTheReceivingSide() throws Exception {
        String txn = onePayment("API-CANCEL", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("the payment to be accepted", () -> "ACCEPTED".equals(status(txn)));
        Rec result = (Rec) approve(post("/api/transactions/" + txn + "/cancel", "operator1",
                Rec.of("reasonCode", "DUPL", "reasonText", "Paid twice", "comment", "customer called"))).get("result");
        assertEquals("FORWARDED", result.str("outcome"));
        await("the receiving side to confirm", () -> "CANCELLED".equals(status(txn)));
        Rec detail = get("/api/transactions/" + txn, "operator1").body();
        assertEquals("ACCEPTED", detail.str("cancellation.status"));
        await("the posting to be reversed", () -> "REVERSED".equals(quietGet("/api/transactions/" + txn, "operator1").str("posting.status")));
        assertEquals(409, post("/api/transactions/" + txn + "/cancel", "operator1", null).status(), "a cancelled payment cannot be cancelled");
    }

    @Test
    @Order(6)
    void aRejectedFileIsReplayedAndAFailedPaymentResubmittedThroughApproval() throws Exception {
        String broken = upload("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><oops>", "broken.xml");
        assertEquals("PARSE_ERROR", get("/api/messages/" + broken, "operator1").body().str("reasonCode"));
        Rec replay = (Rec) approve(post("/api/messages/" + broken + "/replay", "operator1", Rec.of("comment", "try again"))).get("result");
        assertEquals(replay.str("messageId"), get("/api/messages/" + broken, "operator1").body().str("replayedAs"));
        assertEquals(409, post("/api/messages/" + broken + "/replay", "operator1", null).status(), "replayed once");

        // a currency the rate sheet does not quote ends in repair; resubmission is a request too
        String txn = onePayment("API-REPAIR", "Tokyo Parts KK", "JPY", "CHASUS33", "4051122334");
        await("the payment to be parked for repair", () -> "REPAIR".equals(status(txn)));
        // the rate system answered and said no: that is not an outage, so nothing is tried again and its words are kept
        Rec parked = get("/api/transactions/" + txn, "operator1").body();
        assertEquals("CONNECTOR_REFUSED", parked.str("reasonCode"));
        assertTrue(parked.str("reasonText").contains("no rate is quoted for ZAR/JPY"), parked.str("reasonText"));
        assertEquals(null, parked.at("retry.nextAt"), "a refusal is not retried by itself");
        approve(post("/api/transactions/" + txn + "/resubmit", "operator1", Rec.of("comment", "rate sheet updated")));
        await("the resubmitted payment to be processed again", () -> "REPAIR".equals(status(txn)));
        assertTrue(get("/api/transactions/" + txn, "operator1").body().get("events").toString().contains("approved by checker1"));
    }

    @Test
    @Order(7)
    void aUserIsCreatedChangedAndDisabledThroughApproval() throws Exception {
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "newclerk", "roles", List.of("OPERATOR"), "password", "short")).status());
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "newclerk", "roles", List.of("NO_SUCH_ROLE"), "password", "a-long-enough-pass-1")).status());
        Reply request = post("/api/users", "maker1", Rec.of("username", "newclerk", "displayName", "New Clerk",
                "roles", List.of("OPERATOR"), "password", "clerk-First-pass-2026"));
        assertFalse(get("/api/approvals/" + request.body().str("id"), "checker1").body().toString().contains("pbkdf2"), "no password hash in the request shown");
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Rec.of("username", "newclerk", "password", "clerk-First-pass-2026")))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "the user does not exist before approval");
        approve(request);

        PASSWORDS_OF_NEW.put("newclerk", "clerk-First-pass-2026");
        String token = loginNew("newclerk");
        assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(get("/api/users", "maker1").body().toString().contains("passwordHash"));

        // disabling ends the session at once
        approve(post("/api/users", "maker1", Rec.of("username", "newclerk", "displayName", "New Clerk", "roles", List.of("OPERATOR"), "status", "DISABLED")));
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private static final Map<String, String> PASSWORDS_OF_NEW = new HashMap<>();

    private static String loginNew(String user) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Rec.of("username", user, "password", PASSWORDS_OF_NEW.get(user))))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        return Json.parse(r.body()).str("token");
    }

    @Test
    @Order(8)
    void apisDefinedByModelsStoreValidateAndAreEnforced() throws Exception {
        String account = "7700112233";
        assertEquals(404, get("/api/x/limits/" + account, "operator1").status());
        assertEquals(422, send("PUT", "/api/x/limits/" + account, "checker1", "application/json", "{\"perTransaction\": 0}").status());
        Reply set = send("PUT", "/api/x/limits/" + account, "checker1", "application/json", "{\"perTransaction\": 500}");
        assertEquals(200, set.status());
        assertEquals("checker1", set.body().str("updatedBy"));

        String over = onePayment("API-LIMIT", "Normal Supplier", "ZAR", "FIRNZAJJ", account);
        await("the payment over the account limit", () -> "REJECTED_BY_APPLICATION".equals(status(over)));
        assertEquals("AM02", get("/api/transactions/" + over, "operator1").body().str("reasonCode"));

        // a daily limit: the payments of the day add up; the one that would exceed it is rejected, the day's others count, rejected ones do not
        assertEquals(422, send("PUT", "/api/x/limits/" + account, "checker1", "application/json", "{\"perTransaction\": 1000, \"perDay\": 500}").status());
        assertEquals(200, send("PUT", "/api/x/limits/" + account, "checker1", "application/json", "{\"perTransaction\": 1000, \"perDay\": 1500}").status());
        assertEquals(1500, Ops.num(get("/api/x/limits/" + account, "operator1").body().get("perDay")).intValue());
        String first = onePayment("API-DAY-1", "Normal Supplier", "ZAR", "FIRNZAJJ", account);
        await("the first payment of the day to pass the limits", () -> !List.of("CREATED", "PROCESSING").contains(status(first)));
        assertNotEquals("REJECTED_BY_APPLICATION", status(first));
        String second = onePayment("API-DAY-2", "Normal Supplier", "ZAR", "FIRNZAJJ", account);
        await("the second payment of the day to be decided", () -> !List.of("CREATED", "STAGED", "PROCESSING").contains(status(second)));
        Rec rejected = get("/api/transactions/" + second, "operator1").body();
        assertEquals("REJECTED_BY_APPLICATION", rejected.str("status"), rejected.toString());
        assertEquals("AM02", rejected.str("reasonCode"));
        assertTrue(rejected.str("reasonText").contains("daily"), rejected.str("reasonText"));
        assertEquals(200, send("DELETE", "/api/x/limits/" + account, "checker1", "application/json", null).status());
        assertEquals(404, get("/api/x/limits/" + account, "operator1").status());

        // the status API gives a reduced view: no account numbers
        Reply view = get("/api/x/payments/" + over, "operator1");
        assertEquals("REJECTED_BY_APPLICATION", view.body().str("status"));
        assertFalse(view.body().toString().contains(account));
        assertEquals(404, get("/api/x/payments/ORVTXN9999999999", "operator1").status());
        assertEquals(405, post("/api/x/payments/" + over, "operator1", new Rec()).status());

        // documents
        assertEquals(422, send("PUT", "/api/x/documents/INV-1", "operator1", "application/json", "{\"name\": \"a.pdf\", \"content\": \"not base64 !!\"}").status());
        assertEquals(200, send("PUT", "/api/x/documents/INV-1", "operator1", "application/json", "{\"name\": \"a.pdf\", \"content\": \"QUJDRA==\"}").status());
        assertEquals("a.pdf", get("/api/x/documents/INV-1", "operator1").body().str("name"));
    }

    @Test
    @Order(9)
    void aSystemPushesAFileWithTheChannelKeyAndNothingElse() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-PUSHED");
        String channel = "/in/channels.CorporateIsoInbound";
        assertEquals(403, send("POST", channel, null, "text/plain", raw).status());
        assertEquals(403, send("POST", channel, null, "text/plain", raw, "X-Api-Key", "wrong-key-wrong-key-wrong").status());
        assertEquals(403, send("POST", channel, "operator1", "text/plain", raw).status(), "a user session is not a channel key");
        assertEquals(403, send("POST", "/in/channels.CorporateMtInbound", null, "text/plain", raw, "X-Api-Key", CHANNEL_KEY).status());
        Reply pushed = send("POST", channel, null, "text/plain", raw, "X-Api-Key", CHANNEL_KEY);
        assertEquals(202, pushed.status());
        Rec stored = (Rec) Ops.list(pushed.body().get("messages")).get(0);
        assertEquals("api-key:channels.CorporateIsoInbound", stored.str("receivedBy"));
        await("the pushed file to be debulked", () -> "DEBULKED".equals(quietGet("/api/messages/" + stored.str("id"), "operator1").str("status")));
        Reply wrongType = send("POST", channel, null, "text/plain",
                Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")), "X-Api-Key", CHANNEL_KEY);
        assertEquals("NO_CHANNEL", ((Rec) Ops.list(wrongType.body().get("messages")).get(0)).str("reasonCode"));
    }

    @Test
    @Order(10)
    void directDebitsAreCollectedUnderMandatesRegisteredThroughTheApi() throws Exception {
        Rec mandate = Rec.of("creditorId", "DE98ZZZ09999999999", "creditorName", "Nordlicht Energie GmbH", "debtorName", "Camille Laurent",
                "debtorAccount", "FR1420041010050500013M02606", "debtorAgentBic", "BNPAFRPP", "signedOn", "2026-01-15", "type", "RCUR");
        assertEquals(403, send("PUT", "/api/x/mandates/MND-1001", "viewer1", "application/json", Json.write(mandate)).status());
        Rec bad = mandate.copy();
        bad.put("debtorAccount", "FR00");
        assertEquals(422, send("PUT", "/api/x/mandates/MND-1001", "operator1", "application/json", Json.write(bad)).status());
        assertEquals(200, send("PUT", "/api/x/mandates/MND-1001", "operator1", "application/json", Json.write(mandate)).status());
        Rec second = mandate.copy();
        second.put("debtorAccount", "IT60X0542811101000000123456");
        assertEquals(200, send("PUT", "/api/x/mandates/MND-1004", "operator1", "application/json", Json.write(second)).status());
        assertEquals("CANCELLED", send("DELETE", "/api/x/mandates/MND-1004", "operator1", "application/json", null).body().str("status"));

        // Tuesday morning in Berlin, the business day before the Wednesday collection date
        io.orvanta.core.expr.Time.setGlobal(java.time.Instant.parse("2026-10-06T08:00:00Z"));
        try {
            String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain008-collections.xml")).replace("<SeqTp>RCUR</SeqTp>", "<SeqTp>FRST</SeqTp>");
            String id = upload(raw, "collections.xml");
            await("the collections to finish", () -> "COMPLETE".equals(quietGet("/api/messages/" + id, "operator1").str("reportState")));
            Map<String, Rec> txns = transactionsOf(id);
            assertEquals("ACCEPTED", txns.get("DD-0001").str("status"), String.valueOf(txns.get("DD-0001").str("reasonText")));
            assertEquals("MD01", txns.get("DD-0003").str("reasonCode"), "no mandate");
            assertEquals("MD01", txns.get("DD-0004").str("reasonCode"), "cancelled mandate");
            Rec counted = get("/api/x/mandates/MND-1001", "operator1").body();
            assertEquals(1, Ops.num(counted.get("collections")).intValue());
            assertNotNull(counted.str("lastCollectionOn"));
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
        }
    }

    @Test
    @Order(11)
    void aModelIsRemovedAndADeploymentIsRolledBackOnlyWithApproval() throws Exception {
        String base = get("/api/health", null).body().str("deployment");
        String extra = "kind: RuleSet\nname: payments.rules.ScenarioExtra\nrules:\n  - id: POSITIVE\n    assert: txn.amount > 0\n";
        approve(post("/api/studio/changes", "maker1", Rec.of("text", extra, "comment", "extra rules for the scenario")));
        String withExtra = get("/api/health", null).body().str("deployment");

        // a deployment shows what it changed compared with the one before
        Rec shown = get("/api/studio/deployments/" + withExtra, "maker1").body();
        assertEquals(base, shown.str("previous"));
        assertEquals(true, shown.get("active"));
        assertEquals(1, Ops.list(shown.get("changes")).size(), shown.toString());
        assertEquals("ADDED", firstOf(shown, "changes").str("change"));
        assertEquals("payments.rules.ScenarioExtra", firstOf(shown, "changes").str("name"));
        assertEquals(null, shown.get("models"), "the list of every model text is not sent with it");
        assertEquals(404, get("/api/studio/deployments/ORVDEP999999", "maker1").status());

        // a model that something still uses cannot be removed, and the user of it is named
        Reply used = post("/api/studio/removals", "maker1", Rec.of("name", "payments.rules.TransactionValidation"));
        assertEquals(422, used.status());
        assertTrue(used.body().toString().contains("payments.flows.TransactionProcessing"), used.body().toString());
        assertEquals(404, post("/api/studio/removals", "maker1", Rec.of("name", "payments.rules.NoSuchModel")).status());
        assertEquals(403, post("/api/studio/removals", "operator1", Rec.of("name", "payments.rules.ScenarioExtra")).status());

        // removal takes a second person
        Reply removal = post("/api/studio/removals", "maker1", Rec.of("name", "payments.rules.ScenarioExtra", "comment", "not needed"));
        assertEquals(200, removal.status(), removal.body().toString());
        assertEquals(403, post("/api/approvals/" + removal.body().str("id") + "/approve", "maker1", null).status());
        assertEquals(200, get("/api/studio/models/payments.rules.ScenarioExtra", "maker1").status(), "still deployed before approval");
        assertEquals(extra, get("/api/approvals/" + removal.body().str("id"), "checker1").body().str("currentText"));
        approve(removal);
        assertEquals(404, get("/api/studio/models/payments.rules.ScenarioExtra", "maker1").status());
        String withoutExtra = get("/api/health", null).body().str("deployment");

        // going back: the earlier deployment shows what a rollback would change now
        Rec earlier = get("/api/studio/deployments/" + withExtra, "maker1").body();
        assertEquals(false, earlier.get("active"));
        assertEquals("ADDED", firstOf(earlier, "rollback").str("change"));
        assertEquals(409, post("/api/studio/rollbacks", "maker1", Rec.of("deploymentId", withoutExtra)).status(), "not to the active one");
        assertEquals(409, post("/api/studio/rollbacks", "maker1", Rec.of("deploymentId", base)).status(), "not when nothing would change");
        assertEquals(403, post("/api/studio/rollbacks", "operator1", Rec.of("deploymentId", withExtra)).status());

        // a rollback approved after another change came in is refused: the approver saw a difference that is no longer true
        Reply stale = post("/api/studio/rollbacks", "maker1", Rec.of("deploymentId", withExtra, "comment", "bring the rules back"));
        assertEquals(200, stale.status(), stale.body().toString());
        Rec pending = get("/api/approvals/" + stale.body().str("id"), "checker1").body();
        assertEquals("payments.rules.ScenarioExtra", firstOf(pending, "changes").str("name"), "the checker sees what approving changes");
        String other = extra.replace("ScenarioExtra", "ScenarioOther");
        approve(post("/api/studio/changes", "maker1", Rec.of("text", other, "comment", "something else in between")));
        String between = get("/api/health", null).body().str("deployment");
        Reply refused = post("/api/approvals/" + stale.body().str("id") + "/approve", "checker1", null);
        assertEquals("FAILED", refused.body().str("status"));
        assertTrue(refused.body().str("failure").contains("request it again"), refused.body().toString());
        assertEquals(between, get("/api/health", null).body().str("deployment"), "nothing was deployed");

        // requested again and approved: the earlier models are active as a new version, history is kept
        Rec done = approve(post("/api/studio/rollbacks", "maker1", Rec.of("deploymentId", withExtra, "comment", "bring the rules back")));
        String restored = get("/api/health", null).body().str("deployment");
        assertEquals(restored, String.valueOf(done.at("result.deploymentId")));
        assertFalse(restored.equals(withExtra), "a rollback is a new deployment");
        assertEquals(200, get("/api/studio/models/payments.rules.ScenarioExtra", "maker1").status());
        assertEquals(404, get("/api/studio/models/payments.rules.ScenarioOther", "maker1").status());
        Rec last = get("/api/studio/deployments/" + restored, "maker1").body();
        assertEquals(between, last.str("previous"));
        assertEquals(2, Ops.list(last.get("changes")).size(), "one model came back and one went");

        // leave the models as the other tests expect them
        approve(post("/api/studio/removals", "maker1", Rec.of("name", "payments.rules.ScenarioExtra", "comment", "end of the scenario")));
        String payment = onePayment("AFTER-ROLLBACK", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("a payment on the deployment after the rollback", () -> "ACCEPTED".equals(status(payment)));
    }

    /** A reduced schema for the test, not the ISO schema: the message id is limited, everything else is open. */
    private static final String TEST_XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"
                       targetNamespace="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09" elementFormDefault="qualified">
              <xs:element name="Document"><xs:complexType><xs:sequence>
                <xs:element name="CstmrCdtTrfInitn"><xs:complexType><xs:sequence>
                  <xs:element name="GrpHdr"><xs:complexType><xs:sequence>
                    <xs:element name="MsgId"><xs:simpleType><xs:restriction base="xs:string"><xs:maxLength value="35"/></xs:restriction></xs:simpleType></xs:element>
                    <xs:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/>
                </xs:sequence></xs:complexType></xs:element>
              </xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """;

    @Test
    @Order(12)
    void aMessageThatBreaksItsSchemaOrFieldRulesIsRejectedWithTheFieldNamed() throws Exception {
        // SWIFT MT: the field rules are models of the deployment
        String mt101 = Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")).replace("SUPPLIERS-1004", "SUPPLIERS-9001");
        Rec good = firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", mt101)).body(), "messages");
        assertEquals("MT101", good.str("messageType"));
        assertEquals("message specification of MT101", good.str("checkedAgainst"));
        assertEquals(List.of(), Ops.list(good.get("problems")));

        String rejected = upload(mt101.replace(":32B:EUR8300,40", ":32B:EUR8300.40"), "bad-amount.fin");
        Rec message = get("/api/messages/" + rejected, "operator1").body();
        assertEquals("REJECTED", message.str("status"));
        assertEquals("FORMAT_INVALID", message.str("reasonCode"));
        assertTrue(message.str("reasonText").contains(":32B:") && message.str("reasonText").contains("3!a15d"), message.str("reasonText"));
        assertEquals(403, post("/api/studio/check-message", "operator1", Rec.of("raw", mt101)).status());
        assertEquals(422, post("/api/studio/check-message", "maker1", Rec.of("raw", "neither one nor the other")).status());

        // what the engine itself sends follows the same rules: a cover payment is an MT103 and an MT202 COV
        String cover = onePayment("SPEC-COVER", "Overseas Supplier Inc", "USD", "CITIUS33", "4051122334");
        Rec sent = await("the cover payment to be sent", () -> {
            Rec t = quietGet("/api/transactions/" + cover, "operator1");
            return t.str("outboundId") == null ? null : t;
        });
        String payload = get("/api/outbound/" + sent.str("outboundId"), "operator1").body().str("payload");
        Rec own = post("/api/studio/check-message", "maker1", Rec.of("raw", payload)).body();
        List<String> types = new ArrayList<>();
        for (Object m : Ops.list(own.get("messages"))) {
            Rec checked = Rec.from((Map<?, ?>) m);
            types.add(checked.str("messageType"));
            assertEquals(List.of(), Ops.list(checked.get("problems")), payload);
            assertNotNull(checked.str("checkedAgainst"));
        }
        assertEquals(List.of("MT103", "MT202"), types, payload);

        // ISO 20022: nothing is checked until a schema is imported, and importing takes a second person
        String longId = "SCHEMA-TEST-0123456789-0123456789-0123456789";
        String pain = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>" + longId
                + "</MsgId></GrpHdr><PmtInf><PmtInfId>P1</PmtInfId></PmtInf></CstmrCdtTrfInitn></Document>";
        assertEquals(null, firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", pain)).body(), "messages").get("checkedAgainst"));
        assertEquals(422, post("/api/schemas", "maker1", Rec.of("text", "<notASchema/>")).status());
        assertEquals(422, post("/api/schemas", "maker1", Rec.of("text", TEST_XSD.replace("urn:iso:std:iso:20022:tech:xsd:pain.001.001.09", "urn:example:other"))).status());
        assertEquals(422, post("/api/schemas", "maker1", Rec.of("text", TEST_XSD.replace("xs:maxLength", "xs:noSuchFacet"))).status());
        assertEquals(403, post("/api/schemas", "operator1", Rec.of("text", TEST_XSD)).status());
        Reply request = post("/api/schemas", "maker1", Rec.of("text", TEST_XSD, "fileName", "pain.001.001.09.xsd", "comment", "schema for the scenario"));
        assertEquals("Import schema pain.001.001.09", request.body().str("summary"));
        assertEquals(403, post("/api/approvals/" + request.body().str("id") + "/approve", "maker1", null).status());
        assertEquals(List.of(), Ops.list(get("/api/schemas", "maker1").body().get("schemas")), "nothing is installed before approval");
        approve(request);
        Rec listed = firstOf(get("/api/schemas", "maker1").body(), "schemas");
        assertEquals("pain.001.001.09", listed.str("messageType"));
        assertEquals("checker1", listed.str("approvedBy"));
        assertEquals(null, listed.get("text"), "the list does not carry the schema text");
        assertTrue(get("/api/schemas", "maker1").body().toString().contains("specs.swift.MT103"), "the MT specifications are listed with them");

        // the problem names the element by its path in the message
        Rec checked = firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", pain)).body(), "messages");
        assertEquals("imported schema pain.001.001.09.xsd", checked.str("checkedAgainst"));
        assertTrue(checked.get("problems").toString().contains("Document/CstmrCdtTrfInitn/GrpHdr/MsgId"), checked.toString());
        Rec refused = get("/api/messages/" + upload(pain, "long-id.xml"), "operator1").body();
        assertEquals("REJECTED", refused.str("status"));
        assertEquals("SCHEMA_INVALID", refused.str("reasonCode"));
        assertTrue(refused.str("reasonText").contains("Document/CstmrCdtTrfInitn/GrpHdr/MsgId"), refused.str("reasonText"));
        String valid = onePayment("SCHEMA-OK", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("a valid payment with the schema installed", () -> "ACCEPTED".equals(status(valid)));

        // removal the same way; afterwards the type is no longer checked
        assertEquals(404, post("/api/schemas/pacs.008.001.08/removal", "maker1", new Rec()).status());
        approve(post("/api/schemas/pain.001.001.09/removal", "maker1", Rec.of("comment", "end of the scenario")));
        assertEquals(null, firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", pain)).body(), "messages").get("checkedAgainst"));
    }

    @Test
    @Order(14)
    void aMessageTheEngineBuildsIsCheckedBeforeItIsSentAndHeldBackWhenInvalid() throws Exception {
        // a schema for the clearing message that the engine's own message id (16 characters) cannot satisfy
        String xsd = TEST_XSD.replace("pain.001.001.09", "pacs.008.001.08").replace("CstmrCdtTrfInitn", "FIToFICstmrCdtTrf").replace("value=\"35\"", "value=\"5\"");
        approve(post("/api/schemas", "maker1", Rec.of("text", xsd, "fileName", "pacs.008.001.08.xsd", "comment", "too strict on purpose")));

        String txn = onePayment("OUT-CHECK", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("the payment to be held back for repair", () -> "REPAIR".equals(status(txn)));
        Rec held = get("/api/transactions/" + txn, "operator1").body();
        assertEquals("OUTBOUND_INVALID", held.str("reasonCode"));
        assertTrue(held.str("reasonText").contains("Document/FIToFICstmrCdtTrf/GrpHdr/MsgId")
                && held.str("reasonText").contains("pacs.008.001.08") && held.str("reasonText").contains("channels.ZaRtcOutbound"), held.str("reasonText"));
        assertEquals(404, get("/api/outbound/" + held.str("outboundId"), "operator1").status(), "no file was created, so nothing was sent");

        // with the cause removed, the payment is resubmitted and goes out
        approve(post("/api/schemas/pacs.008.001.08/removal", "maker1", Rec.of("comment", "end of the scenario")));
        approve(post("/api/transactions/" + txn + "/resubmit", "operator1", Rec.of("comment", "schema corrected")));
        await("the resubmitted payment to be accepted", () -> "ACCEPTED".equals(status(txn)));
        String payload = get("/api/outbound/" + get("/api/transactions/" + txn, "operator1").body().str("outboundId"), "operator1").body().str("payload");
        assertTrue(payload.contains("OUT-CHECK"));
    }

    @Test
    @Order(15)
    void aListIsSearchedFilteredSortedAndPagedByTheStore() throws Exception {
        Rec all = get("/api/transactions?limit=1000", "operator1").body();
        long total = Ops.num(all.get("total")).longValue();
        assertTrue(total >= 10, "the earlier scenarios left payments: " + total);
        assertEquals(total, Ops.list(all.get("items")).size());

        // pages: the wanted part, in order, with the size of the whole result
        Reply first = get("/api/transactions?limit=3", "operator1");
        Reply second = get("/api/transactions?limit=3&offset=3", "operator1");
        assertEquals(3, items(first).size());
        assertEquals(total, Ops.num(first.body().get("total")).longValue());
        assertEquals(3, Ops.num(second.body().get("offset")).intValue());
        List<String> ids = new ArrayList<>();
        items(first).forEach(i -> ids.add(i.str("id")));
        items(second).forEach(i -> ids.add(i.str("id")));
        assertEquals(6, new java.util.HashSet<>(ids).size(), "no payment on both pages");
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(java.util.Comparator.reverseOrder());
        assertEquals(sorted, ids, "newest first across the pages");
        assertEquals(0, items(get("/api/transactions?offset=" + (total + 5), "operator1")).size());

        // search: any of the fields, without regard to case, and taken literally
        List<Rec> named = items(get("/api/transactions?q=thandiWE", "operator1"));
        assertFalse(named.isEmpty());
        assertTrue(named.stream().allMatch(x -> Ops.str(x.at("creditor.name")).contains("Thandiwe")));
        assertTrue(items(get("/api/transactions?q=62011223344", "operator1")).size() >= named.size(), "by account as well");
        assertEquals(1, items(get("/api/transactions?q=" + named.get(0).str("id"), "operator1")).size());
        assertEquals(0, Ops.num(get("/api/transactions?q=.*", "operator1").body().get("total")).intValue(), "a pattern is not a pattern here");

        // order and ranges
        List<Rec> rising = items(get("/api/transactions?sort=amount&dir=asc&limit=1000", "operator1"));
        for (int i = 1; i < rising.size(); i++) {
            assertTrue(Ops.num(rising.get(i - 1).get("amount")).compareTo(Ops.num(rising.get(i).get("amount"))) <= 0);
        }
        List<Rec> falling = items(get("/api/transactions?sort=amount&limit=1000", "operator1"));
        assertEquals(rising.get(rising.size() - 1).get("amount").toString(), falling.get(0).get("amount").toString());
        List<Rec> between = items(get("/api/transactions?minAmount=20000&maxAmount=22000", "operator1"));
        assertFalse(between.isEmpty());
        assertTrue(between.stream().allMatch(x -> Ops.num(x.get("amount")).doubleValue() >= 20000 && Ops.num(x.get("amount")).doubleValue() <= 22000));
        String day = rising.get(0).str("createdAt").substring(0, 10);
        long thatDay = Ops.num(get("/api/transactions?from=" + day + "&to=" + day, "operator1").body().get("total")).longValue();
        assertTrue(thatDay > 0 && thatDay <= total);
        assertEquals(0, Ops.num(get("/api/transactions?from=2001-01-01&to=2001-01-31", "operator1").body().get("total")).intValue());
        List<Rec> domestic = items(get("/api/transactions?scheme=ZA-RTC&status=ACCEPTED", "operator1"));
        assertFalse(domestic.isEmpty());
        assertTrue(domestic.stream().allMatch(x -> "ZA-RTC".equals(Ops.str(x.at("route.scheme"))) && "ACCEPTED".equals(x.str("status"))));
        for (String bad : List.of("sort=password", "from=yesterday", "minAmount=lots", "offset=x")) {
            assertEquals(400, get("/api/transactions?" + bad, "operator1").status(), bad);
        }

        // a search by a limited user stays inside the limit
        List<Rec> limited = items(get("/api/transactions?q=supplier&limit=1000", "clerkacct"));
        assertFalse(limited.isEmpty());
        assertTrue(limited.stream().allMatch(x -> "4051122334".equals(Ops.str(x.at("debtor.account")))));
        assertTrue(Ops.num(get("/api/transactions?q=supplier", "clerkacct").body().get("total")).longValue()
                < Ops.num(get("/api/transactions?q=supplier", "operator1").body().get("total")).longValue());

        // the other lists answer the same way: a page, the total, search words, an order
        Rec messages = get("/api/messages?purpose=instruction,unknown&limit=2", "operator1").body();
        assertEquals(2, Ops.list(messages.get("items")).size());
        assertTrue(Ops.num(messages.get("total")).longValue() > 2);
        assertFalse(messages.toString().contains("<Document"), "lists do not carry payloads");
        List<Rec> salaries = items(get("/api/messages?q=salary-2026-10-001", "operator1"));
        assertTrue(salaries.stream().anyMatch(m -> "SALARY-2026-10-001".equals(m.str("msgId"))), salaries.toString());
        assertTrue(salaries.size() < Ops.num(messages.get("total")).longValue(), "the search narrows the list");
        assertTrue(items(get("/api/messages?purpose=acknowledgement,return&limit=500", "operator1")).stream()
                .allMatch(m -> List.of("acknowledgement", "return").contains(m.str("purpose"))), "a value with commas means any of them");
        List<Rec> refused = items(get("/api/messages?status=REJECTED&q=FORMAT_INVALID", "operator1"));
        assertFalse(refused.isEmpty(), "a refused file is found by its reason");
        Rec outbound = get("/api/outbound?q=pacs.008.001.08&sort=transactionCount&limit=3", "operator1").body();
        assertTrue(Ops.num(outbound.get("total")).longValue() > 0);
        assertFalse(outbound.toString().contains("<Document"));
        assertEquals(400, get("/api/outbound?sort=payload", "operator1").status());
        List<Rec> roleRequests = items(get("/api/approvals?type=ROLE_CHANGE,ROLE_REMOVE&limit=500", "checker1"));
        assertEquals(3, roleRequests.size(), "two changes and one removal of the role in the earlier scenario");
        assertTrue(items(get("/api/approvals?q=payment_clerk&status=APPROVED", "checker1")).size() >= 3);
        assertTrue(items(get("/api/approvals?limit=1&offset=1", "checker1")).get(0).str("id").compareTo(
                items(get("/api/approvals?limit=1", "checker1")).get(0).str("id")) < 0, "the second page is older");
        List<Rec> denied = items(get("/api/security/events?type=DENIED&q=viewer1&limit=500", "checker1"));
        assertFalse(denied.isEmpty());
        assertTrue(denied.stream().allMatch(e -> "DENIED".equals(e.str("type")) && "viewer1".equals(e.str("username"))));

        // the dashboard: seven days, and the day's figure is the day's count
        Rec dashboard = get("/api/dashboard", "operator1").body();
        List<?> days = Ops.list(dashboard.get("days"));
        assertEquals(7, days.size());
        long received = 0;
        for (Object d : days) {
            Rec entry = Rec.from((Map<?, ?>) d);
            received += Ops.num(entry.get("received")).longValue();
            assertTrue(Ops.num(entry.get("accepted")).longValue() + Ops.num(entry.get("rejected")).longValue() <= Ops.num(entry.get("received")).longValue());
        }
        assertEquals(total, received, "every payment of the scenarios was received in the last seven days");
    }

    @Test
    @Order(16)
    void anOpenPageIsToldWhenSomethingChangesAndNothingElse() throws Exception {
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/stream")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                "the stream needs a signed-in user like everything else");

        // the reader thread appends while the test reads; a copy-on-write list keeps every stream and join over it safe
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> stream = HTTP.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/api/stream")).header("Authorization", "Bearer " + token("viewer1"))
                        .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines());
        Thread reader = new Thread(() -> {
            try {
                stream.get().body().forEach(lines::add);
            } catch (Exception e) {
                lines.add("closed: " + e);
            }
        });
        reader.setDaemon(true);
        reader.start();
        await("the stream to open", () -> lines.contains("event: hello"));
        assertEquals("text/event-stream", stream.get().headers().firstValue("Content-Type").orElse("").split(";")[0]);

        // a payment arrives: the page is told that payments changed
        int before = lines.size();
        String txn = onePayment("LIVE-1", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("the notice", () -> lines.stream().skip(before).anyMatch(l -> l.startsWith("data:") && l.contains("payments")));
        assertTrue(lines.contains("event: changed"));
        // a request for approval: told as well, as its own kind
        int beforeRequest = lines.size();
        post("/api/transactions/" + txn + "/cancel", "operator1", Rec.of("comment", "to see the notice"));
        await("the notice about the request", () -> lines.stream().skip(beforeRequest).anyMatch(l -> l.startsWith("data:") && l.contains("approvals")));

        // the user of this stream may not see payments at all: the notices say what kind of thing changed and carry no data
        String everything = String.join("\n", lines);
        assertFalse(everything.contains("LIVE-1") || everything.contains(txn) || everything.contains("Normal Supplier") || everything.contains("ORVAPR"), everything);
        assertTrue(lines.stream().filter(l -> l.startsWith("data:")).allMatch(l -> l.matches("data: ?(\\{\\}|\\{\"kinds\":\\[(\"[a-z]+\",?)+\\]\\})")), everything);
        reader.interrupt();
    }

    @Test
    @Order(17)
    void anIncomingPaymentIsCreditedOrSentBackAndACreditedOneCanBeRefunded() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pacs008-incoming.xml"));
        String message = upload(raw, "from-clearing.xml");
        assertEquals("rails.sepa.SctInbound", get("/api/messages/" + message, "operator1").body().str("channel"));
        Map<String, Rec> txns = await("the four incoming payments", () -> {
            Map<String, Rec> found = transactionsOf(message);
            return found.size() == 4 && found.values().stream().allMatch(x -> List.of("CREDITED", "RETURNED", "HELD").contains(x.str("status"))) ? found : null;
        });

        // an open account: credited against the settlement account
        Rec credited = get("/api/transactions/" + txns.get("RENT-OCT").str("id"), "operator1").body();
        assertEquals("CREDITED", credited.str("status"));
        assertEquals("IN", credited.str("paymentType"));
        assertEquals("POSTED", credited.str("posting.status"));
        assertEquals("SNDTX-1", credited.str("originalTxId"));
        assertNotNull(credited.str("creditedAt"));
        assertEquals(null, credited.get("return"));

        // a closed account and a bad account number: sent back with the reason, nothing credited
        Rec closed = get("/api/transactions/" + txns.get("INV-2291").str("id"), "operator1").body();
        assertEquals("RETURNED", closed.str("status"));
        assertEquals("AC04", closed.str("return.reasonCode"));
        assertEquals("application", closed.str("return.requestedBy"));
        assertEquals(null, closed.get("posting"));
        Rec badIban = get("/api/transactions/" + txns.get("GIFT-1").str("id"), "operator1").body();
        assertEquals("RETURNED", badIban.str("status"));
        assertEquals("AC01", badIban.str("return.reasonCode"));
        Rec file = get("/api/outbound/" + closed.str("outboundId"), "operator1").body();
        assertEquals("rails.sepa.SctReturnOutbound", file.str("channel"));
        String pacs004 = file.str("payload");
        assertTrue(pacs004.contains("pacs.004.001.09") && pacs004.contains("<OrgnlTxId>SNDTX-2</OrgnlTxId>") && pacs004.contains("<OrgnlMsgId>CLEARING-IN-2026-10-06-001</OrgnlMsgId>")
                && pacs004.contains("<Cd>AC04</Cd>") && pacs004.contains("<OrgnlEndToEndId>INV-2291</OrgnlEndToEndId>"), pacs004);
        assertFalse(pacs004.contains("RENT-OCT"), "the credited payment is not in the return file");

        // a sender on a sanctions list: neither credited nor sent back by the machine
        String held = txns.get("DONATION-7").str("id");
        assertEquals("HELD", status(held));
        assertEquals("SANC", get("/api/transactions/" + held, "operator1").body().str("hold.code"));

        // an incoming payment is refunded, not cancelled, and only once it is credited
        String id = credited.str("id");
        assertEquals(409, post("/api/transactions/" + id + "/cancel", "operator1", Rec.of("comment", "x")).status());
        assertEquals(409, post("/api/transactions/" + held + "/refund", "operator1", Rec.of("comment", "x")).status());
        assertEquals(409, post("/api/transactions/" + closed.str("id") + "/refund", "operator1", null).status());
        assertEquals(403, post("/api/transactions/" + id + "/refund", "viewer1", null).status());
        assertEquals(400, post("/api/transactions/" + id + "/refund", "operator1", Rec.of("reasonCode", "because")).status());
        Reply refund = post("/api/transactions/" + id + "/refund", "operator1", Rec.of("reasonText", "Paid twice by mistake", "comment", "asked by the customer"));
        assertEquals(200, refund.status(), refund.body().toString());
        assertEquals("CREDITED", status(id), "nothing happens before the second person approves");
        approve(refund);
        await("the refunded payment to be sent back", () -> "RETURNED".equals(status(id)));
        Rec refunded = get("/api/transactions/" + id, "operator1").body();
        assertEquals("CUST", refunded.str("return.reasonCode"));
        assertTrue(refunded.str("return.requestedBy").contains("operator1") && refunded.str("return.requestedBy").contains("checker1"));
        assertEquals("REVERSED", refunded.str("posting.status"), "the credit was taken back");
        String refundFile = get("/api/outbound/" + refunded.str("outboundId"), "operator1").body().str("payload");
        assertTrue(refundFile.contains("<Cd>CUST</Cd>") && refundFile.contains("Paid twice by mistake") && refundFile.contains("<OrgnlTxId>SNDTX-1</OrgnlTxId>"), refundFile);
        List<String> events = new ArrayList<>();
        for (Object e : Ops.list(refunded.get("events"))) {
            events.add(Rec.from((Map<?, ?>) e).str("type"));
        }
        assertTrue(events.indexOf("POSTING_REVERSED") < events.lastIndexOf("RETURNED") && events.contains("CREDITED"), "credited, reversed, then sent back: " + events);
        assertEquals(409, post("/api/transactions/" + id + "/refund", "operator1", null).status(), "a payment is refunded once");

        // the held payment is turned down by two people: it goes back as well, with nothing credited
        approve(post("/api/transactions/" + held + "/reject", "operator1", Rec.of("comment", "not accepted from this sender")));
        await("the held payment to be sent back", () -> "RETURNED".equals(status(held)));
        Rec turnedDown = get("/api/transactions/" + held, "operator1").body();
        assertEquals("SANC", turnedDown.str("return.reasonCode"));
        assertEquals(null, turnedDown.get("posting"));

        // the same payments in another file are duplicates: sent back, not credited twice
        String again = upload(raw.replace("CLEARING-IN-2026-10-06-001", "CLEARING-IN-2026-10-06-002"), "from-clearing-again.xml");
        Rec duplicate = await("the duplicate to be sent back", () -> {
            Rec x = transactionsOf(again).get("RENT-OCT");
            return x != null && "RETURNED".equals(x.str("status")) ? x : null;
        });
        assertEquals("AM05", duplicate.str("return.reasonCode"));
        // the very same file again is refused as a whole
        assertEquals("REJECTED", await("the repeated file", () -> {
            Rec m = quietGet("/api/messages/" + uploadQuietly(raw), "operator1");
            return List.of("REJECTED", "DEBULKED").contains(m.str("status")) ? m : null;
        }).str("status"));

        assertTrue(items(get("/api/transactions?status=RETURNED&q=RENT-OCT", "operator1")).size() >= 2);
        assertTrue(Ops.num(get("/api/dashboard", "operator1").body().at("transactions.RETURNED")).longValue() >= 4);
    }

    /** The sample file of incoming payments and the recall for it, with ids of their own so that scenarios do not meet. */
    private static String withIds(String sample, String suffix) throws Exception {
        return Files.readString(WORKSPACE.resolve("tests/messages/" + sample)).replace("CLEARING-IN-2026-10-06-001", "CLEARING-IN-" + suffix)
                .replace("RECALL-2026-10-07-001", "RECALL-" + suffix).replace("SNDTX-", "SND" + suffix + "-").replace("SND-INSTR-", "INS" + suffix + "-")
                .replace("RENT-OCT", "RENT-" + suffix).replace("INV-2291", "INV-" + suffix).replace("GIFT-1", "GIFT-" + suffix).replace("DONATION-7", "DONATION-" + suffix);
    }

    private static Map<String, Rec> incomingFile(String suffix) throws Exception {
        String message = upload(withIds("pacs008-incoming.xml", suffix), "in-" + suffix + ".xml");
        return await("the incoming payments of " + suffix, () -> {
            Map<String, Rec> found = transactionsOf(message);
            return found.size() == 4 && found.values().stream().allMatch(x -> List.of("CREDITED", "RETURNED", "HELD").contains(x.str("status"))) ? found : null;
        });
    }

    @Test
    @Order(18)
    void aRecallOfTheSendersBankIsAcceptedRefusedOrAnsweredByItself() throws Exception {
        Map<String, Rec> txns = incomingFile("R1");
        String credited = txns.get("RENT-R1").str("id");
        String held = txns.get("DONATION-R1").str("id");
        String alreadyBack = txns.get("INV-R1").str("id");
        assertEquals("CREDITED", status(credited));
        assertEquals("HELD", status(held));

        // the sender's bank asks for four payments back
        String recall = upload(withIds("camt056-recall.xml", "R1"), "recall-R1.xml");
        Rec message = await("the recall to be handled", () -> {
            Rec m = quietGet("/api/messages/" + recall, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
        assertEquals("PROCESSED", message.str("status"), message.toString());
        assertEquals("recall", message.str("purpose"));
        assertEquals(1, Ops.num(message.get("returnedCount")).intValue());
        assertEquals(1, Ops.num(message.get("openCount")).intValue());
        assertEquals(2, Ops.num(message.get("refusedCount")).intValue());

        // not credited yet (held): it simply goes back, and the recall is the reason
        await("the held payment to be sent back", () -> "RETURNED".equals(status(held)));
        Rec back = get("/api/transactions/" + held, "operator1").body();
        assertEquals("FOCR", back.str("return.reasonCode"));
        assertEquals("ACCEPTED", back.str("recall.status"));
        assertEquals(null, back.get("posting"));

        // credited: nothing happens by itself, the request waits for a decision
        Rec waiting = get("/api/transactions/" + credited, "operator1").body();
        assertEquals("CREDITED", waiting.str("status"));
        assertEquals("OPEN", waiting.str("recall.status"));
        assertEquals("DUPL", waiting.str("recall.reasonCode"));
        assertEquals("CASE-7781", waiting.str("recall.caseId"));
        assertEquals("POSTED", waiting.str("posting.status"));
        assertTrue(items(get("/api/transactions?recall=OPEN", "operator1")).stream().anyMatch(x -> credited.equals(x.str("id"))));

        // already sent back, and never received: the sender is told so, without anyone deciding
        List<Rec> answers = await("the two answers", () -> {
            List<Rec> found = new ArrayList<>();
            for (Rec o : items(quietReply("/api/outbound?kind=recallAnswer&limit=500"))) {
                String payload = quietGet("/api/outbound/" + o.str("id"), "operator1").str("payload");
                if (payload.contains("SNDR1-")) {
                    found.add(Rec.of("id", o.str("id"), "payload", payload));
                }
            }
            return found.size() == 2 ? found : null;
        });
        String all = answers.get(0).str("payload") + answers.get(1).str("payload");
        assertTrue(all.contains("camt.029.001.09") && all.contains("<Cd>ARDT</Cd>") && all.contains("<OrgnlTxId>SNDR1-2</OrgnlTxId>")
                && all.contains("<Cd>NOOR</Cd>") && all.contains("<OrgnlTxId>SNDR1-99</OrgnlTxId>") && all.contains("<Id>CASE-7781</Id>"), all);
        assertTrue(get("/api/transactions/" + alreadyBack, "operator1").body().get("events").toString().contains("RECALL_REFUSED"));

        // deciding takes the permission, an open recall, and a second person
        assertEquals(403, post("/api/transactions/" + credited + "/recall/accept", "viewer1", null).status());
        assertEquals(409, post("/api/transactions/" + alreadyBack + "/recall/accept", "operator1", null).status());
        assertEquals(404, post("/api/transactions/" + credited + "/recall/ignore", "operator1", null).status());
        assertEquals(400, post("/api/transactions/" + credited + "/recall/refuse", "operator1", Rec.of("reasonCode", "no")).status());

        // refused: the customer keeps the money, and the sender's bank gets the reason
        Reply refuse = post("/api/transactions/" + credited + "/recall/refuse", "operator1",
                Rec.of("reasonText", "The beneficiary does not agree", "comment", "customer was asked"));
        assertEquals("OPEN", get("/api/transactions/" + credited, "operator1").body().str("recall.status"), "nothing before approval");
        Rec decided = approve(refuse);
        Rec refused = get("/api/transactions/" + credited, "operator1").body();
        assertEquals("CREDITED", refused.str("status"));
        assertEquals("POSTED", refused.str("posting.status"));
        assertEquals("REFUSED", refused.str("recall.status"));
        assertEquals(String.valueOf(decided.at("result.answerId")), refused.str("recall.answerId"));
        String answer = get("/api/outbound/" + refused.str("recall.answerId"), "operator1").body().str("payload");
        assertTrue(answer.contains("<Cd>CUST</Cd>") && answer.contains("The beneficiary does not agree") && answer.contains("<OrgnlTxId>SNDR1-1</OrgnlTxId>")
                && answer.contains("<TxCxlSts>RJCR</TxCxlSts>"), answer);
        assertEquals(409, post("/api/transactions/" + credited + "/recall/accept", "operator1", null).status(), "a recall is decided once");

        // accepted: the credit is reversed and the payment goes back; the return is the answer
        Map<String, Rec> second = incomingFile("R2");
        String other = second.get("RENT-R2").str("id");
        upload(withIds("camt056-recall.xml", "R2"), "recall-R2.xml");
        await("the second recall to wait", () -> "OPEN".equals(quietGet("/api/transactions/" + other, "operator1").str("recall.status")));
        approve(post("/api/transactions/" + other + "/recall/accept", "operator1", Rec.of("comment", "customer agrees")));
        await("the recalled payment to be sent back", () -> "RETURNED".equals(status(other)));
        Rec accepted = get("/api/transactions/" + other, "operator1").body();
        assertEquals("ACCEPTED", accepted.str("recall.status"));
        assertEquals("FOCR", accepted.str("return.reasonCode"));
        assertEquals("REVERSED", accepted.str("posting.status"));
        assertEquals(null, accepted.get("recall.answerId"), "no separate answer for an accepted recall");
        assertTrue(get("/api/outbound/" + accepted.str("outboundId"), "operator1").body().str("payload").contains("<Cd>FOCR</Cd>"));
    }

    private static Reply quietReply(String path) {
        try {
            return get(path, "operator1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @Order(19)
    void aCollectionAgainstACustomersAccountIsDebitedOrSentBackAndCanBeRefunded() throws Exception {
        String account = "DE08500105175400000042";
        // the customer does not want to be debited by the gym at all
        assertEquals(403, send("PUT", "/api/x/debit-blocks/" + account + "/DE11ZZZ00000012345", "viewer1", "application/json", "{}").status());
        assertEquals(422, send("PUT", "/api/x/debit-blocks/" + account + "/short", "checker1", "application/json", "{}").status());
        assertEquals(422, send("PUT", "/api/x/debit-blocks/" + account + "/DE11ZZZ00000012345", "checker1", "application/json", "{\"maxAmount\": 0}").status());
        assertEquals(200, send("PUT", "/api/x/debit-blocks/" + account + "/DE11ZZZ00000012345", "checker1", "application/json", "{\"note\": \"customer called\"}").status());

        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pacs003-collections-in.xml"));
        String message = upload(raw, "collections.xml");
        assertEquals("rails.sepa.SddDebtorInbound", get("/api/messages/" + message, "operator1").body().str("channel"));
        Map<String, Rec> txns = await("the four collections", () -> {
            Map<String, Rec> found = transactionsOf(message);
            return found.size() == 4 && found.values().stream().allMatch(x -> List.of("DEBITED", "RETURNED").contains(x.str("status"))) ? found : null;
        });

        // an open account with funds: debited against the settlement account of the scheme
        Rec debited = get("/api/transactions/" + txns.get("POWER-OCT").str("id"), "operator1").body();
        assertEquals("DEBITED", debited.str("status"));
        assertEquals("DD_IN", debited.str("paymentType"));
        assertEquals("POSTED", debited.str("posting.status"));
        assertEquals(account, debited.str("posting.debitAccount"));
        assertEquals("MND-7001", debited.str("mandate.id"));
        assertNotNull(debited.str("debitedAt"));

        // a blocked creditor, an account without funds, a closed account: each sent back with its reason
        Rec blocked = get("/api/transactions/" + txns.get("GYM-OCT").str("id"), "operator1").body();
        assertEquals("RETURNED", blocked.str("status"));
        assertEquals("SL01", blocked.str("return.reasonCode"));
        assertEquals(null, blocked.get("posting"));
        Rec noFunds = get("/api/transactions/" + txns.get("PHONE-OCT").str("id"), "operator1").body();
        assertEquals("AM04", noFunds.str("return.reasonCode"));
        assertEquals("REFUSED", noFunds.str("posting.status"));
        assertEquals("AC04", get("/api/transactions/" + txns.get("INSURANCE-Q4").str("id"), "operator1").body().str("return.reasonCode"));
        Rec file = get("/api/outbound/" + blocked.str("outboundId"), "operator1").body();
        assertEquals("rails.sepa.SddReturnOutbound", file.str("channel"));
        String pacs004 = file.str("payload");
        assertTrue(pacs004.contains("<OrgnlMsgNmId>pacs.003.001.08</OrgnlMsgNmId>") && pacs004.contains("<MndtId>MND-7002</MndtId>") && pacs004.contains("<Cd>SL01</Cd>")
                && pacs004.contains("<OrgnlTxId>SNDDD-2</OrgnlTxId>") && pacs004.contains("<OrgnlMsgId>CLEARING-DD-2026-10-07-001</OrgnlMsgId>"), pacs004);
        assertFalse(pacs004.contains("POWER-OCT"), "the debited collection is not in the return file");

        // the customer now allows the energy company 50 at a time: the next collection of 84.20 goes back
        assertEquals(200, send("PUT", "/api/x/debit-blocks/" + account + "/DE98ZZZ09999999999", "checker1", "application/json", "{\"maxAmount\": 50}").status());
        String next = upload(raw.replace("CLEARING-DD-2026-10-07-001", "CLEARING-DD-2026-11-07-001").replace("-OCT", "-NOV").replace("INSURANCE-Q4", "INSURANCE-Q5")
                .replace("SNDDD-", "SNDDN-"), "collections-nov.xml");
        Rec aboveLimit = await("the collection above the limit", () -> {
            Rec x = transactionsOf(next).get("POWER-NOV");
            return x != null && "RETURNED".equals(x.str("status")) ? x : null;
        });
        assertEquals("SL01", aboveLimit.str("return.reasonCode"));
        assertTrue(aboveLimit.str("return.reasonText").contains("above what the customer allows"));
        assertEquals(200, send("DELETE", "/api/x/debit-blocks/" + account + "/DE98ZZZ09999999999", "checker1", "application/json", null).status());

        // the customer disputes the collection of October: it is refunded, with a second person, and the creditor's bank is told MD06
        String id = debited.str("id");
        assertEquals(409, post("/api/transactions/" + id + "/cancel", "operator1", Rec.of("comment", "x")).status());
        assertEquals(409, post("/api/transactions/" + blocked.str("id") + "/refund", "operator1", null).status());
        Reply refund = post("/api/transactions/" + id + "/refund", "operator1", Rec.of("reasonText", "The customer did not agree to this collection", "comment", "disputed by phone"));
        assertEquals(200, refund.status(), refund.body().toString());
        assertTrue(refund.body().str("summary").contains("back to Lukas Schmidt") && refund.body().str("summary").contains("Nordlicht Energie"), refund.body().str("summary"));
        assertEquals("DEBITED", status(id));
        approve(refund);
        await("the refunded collection to be sent back", () -> "RETURNED".equals(status(id)));
        Rec refunded = get("/api/transactions/" + id, "operator1").body();
        assertEquals("MD06", refunded.str("return.reasonCode"));
        assertEquals("REVERSED", refunded.str("posting.status"), "the debit was given back to the customer");
        String refundFile = get("/api/outbound/" + refunded.str("outboundId"), "operator1").body().str("payload");
        assertTrue(refundFile.contains("<Cd>MD06</Cd>") && refundFile.contains("<Nm>Lukas Schmidt</Nm>") && refundFile.contains("<OrgnlTxId>SNDDD-1</OrgnlTxId>"), refundFile);
        assertEquals(409, post("/api/transactions/" + id + "/refund", "operator1", null).status());
        assertTrue(Ops.num(get("/api/dashboard", "operator1").body().at("transactions.DEBITED")).longValue() >= 0);
    }

    @Test
    @Order(20)
    void customerInstructionsAreSeenInTheConsoleAndChangedOnlyWithASecondPerson() throws Exception {
        // the data sets that their models offer to the Console
        List<Rec> sets = items(get("/api/datasets", "operator1"));
        assertEquals(List.of("data.AccountLimits", "data.CutOffExtensions", "data.DebitBlocks", "data.Mandates", "data.NotificationPreferences"), sets.stream().map(x -> x.str("name")).sorted().toList());
        assertEquals(403, get("/api/datasets", "viewer1").status());
        assertEquals(403, get("/api/datasets", "clerkacct").status(), "rows cannot be limited to a user's payments, so a limited user gets none");
        assertEquals(404, get("/api/datasets/data.Documents/rows", "operator1").status(), "a data set without a console section is not offered");
        assertEquals(404, get("/api/datasets/data.Transactions/rows", "operator1").status());

        // the rows, searched like every other list; the block of the gym is there from the scenario before
        String account = "DE08500105175400000042";
        Rec blocks = get("/api/datasets/data.DebitBlocks/rows?q=DE11ZZZ00000012345", "operator1").body();
        assertEquals(1, Ops.num(blocks.get("total")).intValue());
        assertEquals(account, firstOf(blocks, "items").str("debtorAccount"));

        // a change is asked for by one person: checked at once, made only when another approves
        String path = "/api/datasets/data.DebitBlocks/requests";
        Rec ask = Rec.of("action", "write", "path", Rec.of("account", account, "creditorId", "DE44ZZZ00000099999"), "body", Rec.of("maxAmount", 75, "note", "by letter"));
        assertEquals(403, post(path, "viewer1", ask).status());
        assertEquals(400, post(path, "operator1", Rec.of("action", "empty")).status());
        assertEquals(400, post(path, "operator1", Rec.of("action", "write", "path", Rec.of("account", "a/b", "creditorId", "DE44ZZZ00000099999"))).status());
        Reply refused = post(path, "operator1", Rec.of("action", "write", "path", ask.get("path"), "body", Rec.of("maxAmount", 0)));
        assertEquals(422, refused.status());
        assertTrue(refused.body().toString().contains("maxAmount must be a number greater than zero"), refused.body().toString());
        long pendingBefore = Ops.num(get("/api/approvals?type=DATA_CHANGE&status=PENDING", "checker1").body().get("total")).longValue();
        Reply request = post(path, "operator1", ask);
        assertEquals(200, request.status(), request.body().toString());
        assertTrue(request.body().str("summary").startsWith("Set Direct debit blocks: " + account));
        assertEquals(pendingBefore + 1, Ops.num(get("/api/approvals?type=DATA_CHANGE&status=PENDING", "checker1").body().get("total")).longValue(),
                "the refused attempts made no request");
        assertEquals(0, Ops.num(get("/api/datasets/data.DebitBlocks/rows?q=DE44ZZZ00000099999", "operator1").body().get("total")).intValue(), "nothing before approval");
        assertEquals(403, post("/api/approvals/" + request.body().str("id") + "/approve", "operator1", null).status());
        approve(request);
        Rec stored = firstOf(get("/api/datasets/data.DebitBlocks/rows?q=DE44ZZZ00000099999", "operator1").body(), "items");
        assertEquals(75, Ops.num(stored.get("maxAmount")).intValue());
        assertEquals("by letter", stored.str("note"));
        assertTrue(stored.str("updatedBy").contains("operator1") && stored.str("updatedBy").contains("checker1"), stored.str("updatedBy"));

        // removal the same way
        approve(post(path, "operator1", Rec.of("action", "remove", "path", ask.get("path"), "comment", "customer changed their mind")));
        assertEquals(0, Ops.num(get("/api/datasets/data.DebitBlocks/rows?q=DE44ZZZ00000099999", "operator1").body().get("total")).intValue());

        // the other two: a limit that the payment flow then reads, and a mandate that is refused when incomplete
        approve(post("/api/datasets/data.AccountLimits/requests", "operator1",
                Rec.of("action", "write", "path", Rec.of("account", "7711223344"), "body", Rec.of("perTransaction", 1200))));
        assertEquals(1200, Ops.num(get("/api/x/limits/7711223344", "operator1").body().get("perTransaction")).intValue());
        Reply mandate = post("/api/datasets/data.Mandates/requests", "operator1",
                Rec.of("action", "write", "path", Rec.of("mandateId", "MND-CONSOLE-1"), "body", Rec.of("creditorId", "DE98ZZZ09999999999", "type", "RCUR")));
        assertEquals(422, mandate.status());
        assertTrue(mandate.body().toString().contains("debtorAccount must be a valid IBAN"));
    }

    private static List<Rec> told(String txnId) {
        List<Rec> out = new ArrayList<>();
        for (Object o : Ops.list(quietGet("/api/transactions/" + txnId, "operator1").at("notify.sent"))) {
            out.add(Rec.from((Map<?, ?>) o));
        }
        return out;
    }

    @Test
    @Order(21)
    void theCustomerIsToldOfACreditADebitAndTheirReversalOnceEach() throws Exception {
        // a payment received and credited: the customer gets a credit notification for the account
        Map<String, Rec> txns = incomingFile("N1");
        String credited = txns.get("RENT-N1").str("id");
        await("the credit notification", () -> told(credited).size() == 1);
        Rec first = told(credited).get(0);
        assertEquals("CREDIT", first.str("kind"));
        Rec notification = get("/api/outbound/" + first.str("outboundId"), "operator1").body();
        assertEquals("notification", notification.str("kind"));
        assertEquals("channels.CustomerNotificationOutbound", notification.str("channel"));
        assertEquals("DE08500105175400000042", notification.str("account"));
        String camt054 = notification.str("payload");
        assertTrue(camt054.contains("camt.054.001.08") && camt054.contains("<IBAN>DE08500105175400000042</IBAN>") && camt054.contains("<CdtDbtInd>CRDT</CdtDbtInd>")
                && camt054.contains("<EndToEndId>RENT-N1</EndToEndId>") && camt054.contains("<Nm>Camille Laurent</Nm>") && camt054.contains("<SubFmlyCd>ESCT</SubFmlyCd>"), camt054);
        // other bookings on the same account that were waiting are in the same notification; this entry is the credit
        assertFalse(entryOf(camt054, "RENT-N1").contains("RvslInd"), camt054);
        assertTrue(entryOf(camt054, "RENT-N1").contains("<CdtDbtInd>CRDT</CdtDbtInd>"));
        await("the notification to be delivered", () -> "SENT".equals(quietGet("/api/outbound/" + first.str("outboundId"), "operator1").str("status")));
        assertTrue(get("/api/transactions/" + credited, "operator1").body().get("events").toString().contains("NOTIFIED"));

        // payments that were sent back without ever being credited: the customer never saw them, so nothing is told
        assertEquals(null, get("/api/transactions/" + txns.get("INV-N1").str("id"), "operator1").body().get("notify"));
        assertFalse(camt054.contains("INV-N1"));

        // the credited payment is refunded: a second notification reverses the first, with the reason
        approve(post("/api/transactions/" + credited + "/refund", "operator1", Rec.of("reasonText", "Paid twice by mistake")));
        await("the reversal notification", () -> told(credited).size() == 2);
        assertEquals(List.of("CREDIT", "CREDIT_REVERSAL"), told(credited).stream().map(x -> x.str("kind")).toList());
        String reversal = get("/api/outbound/" + told(credited).get(1).str("outboundId"), "operator1").body().str("payload");
        assertTrue(entryOf(reversal, "RENT-N1").contains("<RvslInd>true</RvslInd>") && entryOf(reversal, "RENT-N1").contains("<CdtDbtInd>DBIT</CdtDbtInd>") && reversal.contains("<Cd>CUST</Cd>")
                && reversal.contains("<NtryRef>" + credited + "-R</NtryRef>") && reversal.contains("Paid twice by mistake"), reversal);

        // a collection that is debited: a debit notification, with the mandate and the creditor
        String collections = Files.readString(WORKSPACE.resolve("tests/messages/pacs003-collections-in.xml")).replace("CLEARING-DD-2026-10-07-001", "CLEARING-DD-N1")
                .replace("-OCT", "-N1").replace("INSURANCE-Q4", "INSURANCE-N1").replace("SNDDD-", "SNDDM-");
        String message = upload(collections, "collections-n1.xml");
        String debited = await("the collection to be debited", () -> {
            Rec x = transactionsOf(message).get("POWER-N1");
            return x != null && "DEBITED".equals(x.str("status")) ? x : null;
        }).str("id");
        await("the debit notification", () -> told(debited).size() == 1);
        String debit = get("/api/outbound/" + told(debited).get(0).str("outboundId"), "operator1").body().str("payload");
        assertTrue(entryOf(debit, "POWER-N1").contains("<CdtDbtInd>DBIT</CdtDbtInd>") && !entryOf(debit, "POWER-N1").contains("RvslInd") && debit.contains("<MndtId>MND-7001</MndtId>") && debit.contains("<SubFmlyCd>ESDD</SubFmlyCd>")
                && debit.contains("<Nm>Nordlicht Energie GmbH</Nm>") && debit.contains("DE98ZZZ09999999999"), debit);

        // nothing is told twice: after a while there is still one entry per booking
        Thread.sleep(4000);
        assertEquals(2, told(credited).size());
        assertEquals(1, told(debited).size());
        long entries = items(get("/api/outbound?kind=notification&limit=500", "operator1")).stream()
                .map(o -> quietGet("/api/outbound/" + o.str("id"), "operator1").str("payload"))
                .mapToLong(p -> count(p, "<EndToEndId>RENT-N1</EndToEndId>")).sum();
        assertEquals(2, entries, "one entry for the credit and one for its reversal, in all notifications together");
    }

    /** The entry of a notification that is about the payment with that end-to-end id. */
    private static String entryOf(String camt054, String endToEndId) {
        for (String entry : camt054.split("<Ntry>")) {
            if (entry.contains("<EndToEndId>" + endToEndId + "</EndToEndId>")) {
                return entry;
            }
        }
        return "";
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    private static Rec byReference(String reference, String... statuses) throws Exception {
        return await("the payment " + reference, () -> {
            List<Rec> found = items(quietReply("/api/transactions?q=" + reference));
            return found.size() == 1 && List.of(statuses).contains(found.get(0).str("status")) ? quietGet("/api/transactions/" + found.get(0).str("id"), "operator1") : null;
        });
    }

    @Test
    @Order(22)
    void aPaymentReceivedOverSwiftIsCreditedConvertedOrSentBack() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/mt103-incoming.fin"));
        Reply received = send("POST", "/api/inbound?fileName=from-correspondent.fin", "operator1", "text/plain", raw);
        assertEquals(4, Ops.list(received.body().get("messages")).size(), "one message per MT103 in the file");
        assertEquals("rails.swift.Mt103Inbound", firstOf(received.body(), "messages").str("channel"));

        // rand for a rand account, charges shared: credited as received less our charge, which is booked on its own
        Rec same = byReference("CHASE-REF-0001", "CREDITED");
        assertEquals("IN", same.str("paymentType"));
        assertEquals("CHASUS33", same.str("sendingAgentBic"));
        assertEquals(null, same.get("fx"));
        assertEquals(0, Ops.num(same.at("charges.deducted")).compareTo(new java.math.BigDecimal("125")));
        assertEquals(0, Ops.num(same.at("posting.amount")).compareTo(new java.math.BigDecimal("24875")), "the customer gets the amount less the charge");
        assertEquals("62011223344", same.str("posting.creditAccount"));
        assertEquals(0, Ops.num(same.at("charges.posting.amount")).compareTo(new java.math.BigDecimal("125")));
        assertEquals("9200000001", same.str("charges.posting.creditAccount"), "the charge goes to the income account of the tariff");

        // the payer bears all charges: the customer gets everything, and the charge is a claim on the sending bank
        Rec ours = byReference("CHASE-REF-0004", "CREDITED");
        assertEquals(0, Ops.num(ours.at("posting.amount")).compareTo(new java.math.BigDecimal("4000")));
        assertEquals(0, Ops.num(ours.at("charges.claim")).compareTo(new java.math.BigDecimal("125")));
        assertEquals("CHASUS33", ours.str("charges.claimFrom"));
        assertEquals(null, ours.at("charges.posting"));

        // the claim is sent by itself as a request for payment of charges, and it follows the field rules
        Rec claimed = await("the claim to be sent", () -> {
            Rec x = quietGet("/api/transactions/" + ours.str("id"), "operator1");
            return "REQUESTED".equals(x.str("charges.claimStatus")) ? x : null;
        });
        Rec claim = get("/api/outbound/" + claimed.str("charges.claimId"), "operator1").body();
        assertEquals("chargeClaim", claim.str("kind"));
        String mt191 = claim.str("payload").replace("\r", "");
        assertTrue(mt191.contains("{2:I191CHASUS33") && mt191.contains(":21:CHASE-REF-0004") && mt191.contains(":32B:ZAR125,") && mt191.contains("REF CHASE-REF-0004"), mt191);
        assertEquals(List.of(), Ops.list(firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", claim.str("payload"))).body(), "messages").get("problems")));
        assertTrue(items(get("/api/transactions?claim=OPEN,REQUESTED", "operator1")).stream().anyMatch(x -> ours.str("id").equals(x.str("id"))));
        assertTrue(items(get("/api/transactions?claim=OPEN,REQUESTED", "operator1")).stream().allMatch(x -> x.at("charges.claim") != null), "only payments with a claim");
        // a payment whose charge was deducted has no claim
        assertEquals(409, post("/api/transactions/" + same.str("id") + "/charge-claim/paid", "operator1", null).status());
        assertEquals(403, post("/api/transactions/" + ours.str("id") + "/charge-claim/paid", "viewer1", null).status());
        // marked as paid by two people, once
        Reply paid = post("/api/transactions/" + ours.str("id") + "/charge-claim/paid", "operator1", Rec.of("reference", "MT910 of 8 October"));
        assertEquals("REQUESTED", get("/api/transactions/" + ours.str("id"), "operator1").body().str("charges.claimStatus"));
        approve(paid);
        Rec settled = get("/api/transactions/" + ours.str("id"), "operator1").body();
        assertEquals("PAID", settled.str("charges.claimStatus"));
        assertEquals("MT910 of 8 October", settled.str("charges.claimPaidReference"));
        assertEquals(409, post("/api/transactions/" + ours.str("id") + "/charge-claim/paid", "operator1", null).status());
        assertTrue(items(get("/api/transactions?claim=OPEN,REQUESTED", "operator1")).stream().noneMatch(x -> ours.str("id").equals(x.str("id"))));

        // dollars for a rand account: credited with the value at the quoted rate
        Rec converted = byReference("CHASE-REF-0002", "CREDITED");
        assertEquals(0, Ops.num(converted.at("fx.creditAmount")).compareTo(new java.math.BigDecimal("22200")), String.valueOf(converted.get("fx")));
        assertEquals("ZAR", converted.str("fx.creditCurrency"));
        assertEquals(0, Ops.num(converted.at("posting.amount")).compareTo(new java.math.BigDecimal("22075")), "the rand value less the rand charge");
        assertEquals("ZAR", converted.str("posting.currency"));
        assertEquals("ZAR", converted.str("charges.currency"));

        // a closed account: an MT103 goes back to the sending bank, marked as a return, and it follows the field rules
        Rec closed = byReference("CHASE-REF-0003", "RETURNED");
        assertEquals("AC04", closed.str("return.reasonCode"));
        Rec file = get("/api/outbound/" + closed.str("outboundId"), "operator1").body();
        assertEquals("rails.swift.Mt103ReturnOutbound", file.str("channel"));
        String back = file.str("payload").replace("\r", "");
        assertTrue(back.contains("{2:I103CHASUS33") && back.contains(":72:/RETN/59/\n/AC04/\n/MREF/CHASE-REF-0003") && back.contains(":59:/000987654321\nOverseas Supplier Inc")
                && back.contains(":50K:/99911223344\nClosed Account Trading") && back.contains("ZAR9100,5"), back);
        Rec checked = firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", file.str("payload"))).body(), "messages");
        assertEquals("message specification of MT103", checked.str("checkedAgainst"));
        assertEquals(List.of(), Ops.list(checked.get("problems")));

        // the customer is told what was booked: the rand value, with the dollars and the rate it came from
        await("the notification of the converted payment", () -> told(converted.str("id")).size() == 1);
        String camt054 = entryOf(get("/api/outbound/" + told(converted.str("id")).get(0).str("outboundId"), "operator1").body().str("payload"), "CHASE-REF-0002");
        assertTrue(camt054.contains("<Amt Ccy=\"ZAR\">22075") && camt054.contains("<SubFmlyCd>XBCT</SubFmlyCd>") && camt054.contains("<Amt Ccy=\"USD\">1200")
                && camt054.contains("<Chrgs>") && camt054.contains("<Amt Ccy=\"ZAR\">125") && camt054.contains("<ChrgInclInd>true</ChrgInclInd>")
                && camt054.contains("<XchgRate>18.5") && camt054.contains("<Id>62055667788</Id>") && !camt054.contains("<IBAN>"), camt054);

        // a refund sends back what was received, in the currency it was received in, after the credit is reversed
        approve(post("/api/transactions/" + converted.str("id") + "/refund", "operator1", Rec.of("reasonText", "Not for this customer")));
        Rec refunded = byReference("CHASE-REF-0002", "RETURNED");
        assertEquals("REVERSED", refunded.str("posting.status"));
        assertEquals("REVERSED", refunded.str("charges.posting.reversal.status"), "the charge goes back with the payment: the whole amount received is returned");
        String refund = get("/api/outbound/" + refunded.str("outboundId"), "operator1").body().str("payload").replace("\r", "");
        assertTrue(refund.contains("USD1200,") && refund.contains("/RETN/59/\n/CUST/\n/MREF/CHASE-REF-0002"), refund);

        // a message that breaks the field rules is refused when it arrives, and the same file twice is a duplicate
        String broken = raw.substring(0, raw.indexOf("{1:", 10)).replace("CHASE-REF-0001", "CHASE-REF-0009").replace(":23B:CRED\n", "");
        Rec refused = get("/api/messages/" + upload(broken, "broken.fin"), "operator1").body();
        assertEquals("REJECTED", refused.str("status"));
        assertEquals("FORMAT_INVALID", refused.str("reasonCode"));
        assertTrue(refused.str("reasonText").contains(":23B:"), refused.str("reasonText"));
        String again = upload(raw, "again.fin");
        assertEquals("AM05", await("the repeated message", () -> {
            Rec m = quietGet("/api/messages/" + again, "operator1");
            return "REJECTED".equals(m.str("status")) ? m : null;
        }).str("reasonCode"));
    }

    @Test
    @Order(23)
    void instantAndDomesticPaymentsFindTheirOwnChannelAndAreAnsweredTheirOwnWay() throws Exception {
        // three kinds of pacs.008 arrive; each finds the channel that says it is its own, and the rest go to the general one
        String instant = Files.readString(WORKSPACE.resolve("tests/messages/pacs008-incoming-inst.xml"));
        String instantMessage = upload(instant, "instant.xml");
        assertEquals("rails.sepa.SctInstInbound", get("/api/messages/" + instantMessage, "operator1").body().str("channel"));
        String domesticMessage = upload(Files.readString(WORKSPACE.resolve("tests/messages/pacs008-incoming-zar.xml")), "domestic.xml");
        assertEquals("channels.ZaRtcInbound", get("/api/messages/" + domesticMessage, "operator1").body().str("channel"));
        assertEquals("rails.sepa.SctInbound", get("/api/messages/" + upload(withIds("pacs008-incoming.xml", "M1"), "plain.xml"), "operator1").body().str("channel"));

        // instant: credited, and the sender is told so at once
        Rec credited = byReference("DINNER-SPLIT", "CREDITED");
        Rec confirmed = await("the confirmation", () -> {
            Rec x = quietGet("/api/transactions/" + credited.str("id"), "operator1");
            return x.at("confirmation.outboundId") == null ? null : x;
        });
        assertTrue(Ops.num(confirmed.at("confirmation.millisAfterReceipt")).longValue() < 10_000, "well inside the ten seconds an instant payment allows");
        Rec answer = get("/api/outbound/" + confirmed.str("confirmation.outboundId"), "operator1").body();
        assertEquals("confirmation", answer.str("kind"));
        assertEquals("rails.sepa.SctInstStatusOutbound", answer.str("channel"));
        assertTrue(answer.str("payload").contains("<TxSts>ACCP</TxSts>") && answer.str("payload").contains("<OrgnlTxId>INSTTX-1</OrgnlTxId>")
                && answer.str("payload").contains("pacs.002.001.10") && !answer.str("payload").contains("StsRsnInf"), answer.str("payload"));
        assertEquals("9100000004", credited.str("posting.debitAccount"), "against the settlement account for instant payments");

        // instant for a closed account: refused with a status report, not sent back with a return
        upload(instant.replace("INST-IN-2026-10-06-0001", "INST-IN-2026-10-06-0002").replace("DINNER-SPLIT", "DINNER-AGAIN").replace("INSTTX-1", "INSTTX-9")
                .replace("DE08500105175400000042", "DE50500105179990000001"), "instant-closed.xml");
        Rec refused = byReference("DINNER-AGAIN", "RETURNED");
        assertEquals("AC04", refused.str("return.reasonCode"));
        Rec refusal = get("/api/outbound/" + refused.str("outboundId"), "operator1").body();
        assertEquals("rails.sepa.SctInstStatusOutbound", refusal.str("channel"));
        assertTrue(refusal.str("payload").contains("<TxSts>RJCT</TxSts>") && refusal.str("payload").contains("<Cd>AC04</Cd>")
                && refusal.str("payload").contains("<OrgnlTxId>INSTTX-9</OrgnlTxId>"), refusal.str("payload"));
        assertEquals(null, refused.get("confirmation"));

        // a credited instant payment that is refunded later goes back as a return, like any credit transfer
        approve(post("/api/transactions/" + credited.str("id") + "/refund", "operator1", Rec.of("reasonText", "Sent to the wrong person")));
        Rec refunded = byReference("DINNER-SPLIT", "RETURNED");
        Rec back = get("/api/outbound/" + refunded.str("outboundId"), "operator1").body();
        assertEquals("rails.sepa.SctReturnOutbound", back.str("channel"));
        assertTrue(back.str("payload").contains("pacs.004.001.09") && back.str("payload").contains("<Cd>CUST</Cd>"));

        // domestic: credited in rand, or sent back with account numbers that are not IBANs
        Rec rand = byReference("SCHOOL-FEES-T4", "CREDITED");
        assertEquals("9100000001", rand.str("posting.debitAccount"));
        assertEquals("ZAR", rand.str("posting.currency"));
        Rec closed = byReference("DEPOSIT-BACK", "RETURNED");
        Rec domesticReturn = get("/api/outbound/" + closed.str("outboundId"), "operator1").body();
        assertEquals("channels.ZaRtcReturnOutbound", domesticReturn.str("channel"));
        assertTrue(domesticReturn.str("payload").contains("<Cd>AC04</Cd>") && domesticReturn.str("payload").contains("Ccy=\"ZAR\"")
                && domesticReturn.str("payload").contains("<Id>62033445566</Id>") && !domesticReturn.str("payload").contains("<IBAN>"), domesticReturn.str("payload"));
    }

    private static List<String> answersTo(String requestMessageId) {
        List<String> payloads = new ArrayList<>();
        for (Rec o : items(quietReply("/api/outbound?kind=cancellationAnswer&q=" + requestMessageId + "&sort=createdAt&dir=asc"))) {
            payloads.add(quietGet("/api/outbound/" + o.str("id"), "operator1").str("payload").replace("\r", ""));
        }
        return payloads;
    }

    @Test
    @Order(24)
    void theCustomerIsToldWhatBecameOfACancellationRequest() throws Exception {
        // salaries are paid and accepted; then the customer asks for one of them back
        String salaries = upload(Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-CXL-1"), "salaries-cxl.xml");
        await("the salary to be accepted", () -> {
            Rec x = transactionsOf(salaries).get("SAL-0002");
            return x != null && "ACCEPTED".equals(x.str("status"));
        });
        String ask = Files.readString(WORKSPACE.resolve("tests/messages/camt055-cancel-salary.xml")).replace("SALARY-2026-10-001", "SALARY-CXL-1").replace("CXL-2026-10-001", "CXL-ANSWER-1");
        String request = upload(ask, "cancel.xml");
        Rec handled = await("the request to be handled", () -> {
            Rec m = quietGet("/api/messages/" + request, "operator1");
            return "PROCESSED".equals(m.str("status")) ? m : null;
        });
        assertEquals(1, Ops.num(handled.get("forwardedCount")).intValue(), "already accepted by the clearing, so the request was passed on");

        // first answer: the request is with the receiving side
        assertNotNull(handled.str("answerId"));
        String first = get("/api/outbound/" + handled.str("answerId"), "operator1").body().str("payload");
        assertTrue(first.contains("camt.029.001.09") && first.contains("<Id>CXL-ANSWER-1</Id>") && first.contains("<TxCxlSts>PDCR</TxCxlSts>")
                && first.contains("<OrgnlEndToEndId>SAL-0002</OrgnlEndToEndId>") && first.contains("<OrgnlMsgId>SALARY-CXL-1</OrgnlMsgId>"), first);
        // second answer, when the receiving side has decided: cancelled
        List<String> both = await("the final answer", () -> answersTo(request).size() == 2 ? answersTo(request) : null);
        assertTrue(both.get(1).contains("<TxCxlSts>CNCL</TxCxlSts>") && both.get(1).contains("<Conf>CNCL</Conf>") && both.get(1).contains("SAL-0002"), both.get(1));
        assertEquals("CANCELLED", status(transactionsOf(salaries).get("SAL-0002").str("id")));

        // a request for a payment we never had is refused, and the answer says why
        String unknown = upload(ask.replace("CXL-ANSWER-1", "CXL-ANSWER-2").replace("SAL-0002", "SAL-9999"), "cancel-unknown.xml");
        Rec refused = await("the refused request", () -> {
            Rec m = quietGet("/api/messages/" + unknown, "operator1");
            return "PROCESSED".equals(m.str("status")) ? m : null;
        });
        String no = get("/api/outbound/" + refused.str("answerId"), "operator1").body().str("payload");
        assertTrue(no.contains("<TxCxlSts>RJCR</TxCxlSts>") && no.contains("<Cd>NOOR</Cd>") && no.contains("SAL-9999"), no);

        // a customer who works with MT asks with an MT192 for a whole MT101, and gets an MT196
        String mt101 = upload(Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")).replace("SUPPLIERS-1004", "SUPPLIERS-CXL1")
                .replace("INV-88120", "INV-CX1").replace("INV-55071", "INV-CX2"), "suppliers-cxl.fin");
        await("the two transactions of the MT101", () -> transactionsOf(mt101).size() == 2);
        String mt192 = upload(Files.readString(WORKSPACE.resolve("tests/messages/mt192-cancel-suppliers.fin")).replace("SUPPLIERS-1004", "SUPPLIERS-CXL1"), "cancel.fin");
        Rec mtHandled = await("the MT192 to be handled", () -> {
            Rec m = quietGet("/api/messages/" + mt192, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
        assertEquals("PROCESSED", mtHandled.str("status"), mtHandled.toString());
        assertEquals("channels.CorporateMtCancellationInbound", mtHandled.str("channel"));
        assertEquals(2, Ops.num(mtHandled.get("cancelledCount")).intValue() + Ops.num(mtHandled.get("forwardedCount")).intValue()
                + Ops.num(mtHandled.get("refusedCount")).intValue(), "the request means both transactions of the message");
        Rec answer = get("/api/outbound/" + mtHandled.str("answerId"), "operator1").body();
        assertEquals("channels.CorporateMtAnswerOutbound", answer.str("channel"));
        String mt196 = answer.str("payload").replace("\r", "");
        assertTrue(mt196.contains("{2:I196KAROZAJJ") && mt196.contains(":21:CXL-SUPPLIERS-1") && mt196.contains("2 TRANSACTION(S) OF SUPPLIERS-CXL1")
                && (mt196.contains(":76:/CNCL/") || mt196.contains(":76:/RJCR/") || mt196.contains(":76:/PACR/")), mt196);
        assertEquals(List.of(), Ops.list(firstOf(post("/api/studio/check-message", "maker1", Rec.of("raw", answer.str("payload"))).body(), "messages").get("problems")));
    }

    private static String collections(String suffix) throws Exception {
        return Files.readString(WORKSPACE.resolve("tests/messages/pacs003-collections-in.xml")).replace("CLEARING-DD-2026-10-07-001", "CLEARING-DD-" + suffix)
                .replace("-OCT", "-" + suffix).replace("INSURANCE-Q4", "INSURANCE-" + suffix).replace("SNDDD-", "SND" + suffix + "-");
    }

    @Test
    @Order(25)
    void refundsHaveTimeLimitsCollectionsCanBeReversedAndRecallsHaveADueDate() throws Exception {
        // ---- a debited collection can be refunded for eight weeks without a reason, for thirteen months when it was never authorised ----
        String first = upload(collections("L1"), "collections-l1.xml");
        String debited = await("the collection to be debited", () -> {
            Rec x = transactionsOf(first).get("POWER-L1");
            return x != null && "DEBITED".equals(x.str("status")) ? x : null;
        }).str("id");
        try {
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.now().plus(java.time.Duration.ofDays(60)));
            Reply late = post("/api/transactions/" + debited + "/refund", "operator1", Rec.of("reasonText", "I do not want this any more"));
            assertEquals(409, late.status());
            assertTrue(late.body().str("error").contains("MD01") && late.body().str("error").contains("56 days"), late.body().toString());
            Reply unauthorised = post("/api/transactions/" + debited + "/refund", "operator1", Rec.of("reasonCode", "MD01", "reasonText", "I never signed a mandate"));
            assertEquals(200, unauthorised.status(), unauthorised.body().toString());
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.now().plus(java.time.Duration.ofDays(400)));
            Reply tooLate = post("/api/transactions/" + debited + "/refund", "operator1", Rec.of("reasonCode", "MD01"));
            assertEquals(409, tooLate.status());
            assertTrue(tooLate.body().str("error").contains("no longer"), tooLate.body().toString());
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
        }
        // a payment received by credit transfer has no such limit set on its channel
        assertEquals("DEBITED", status(debited));

        // ---- the creditor's bank takes a collection back: the customer gets the money, nothing is sent ----
        String second = upload(collections("L2"), "collections-l2.xml");
        String collected = await("the second collection to be debited", () -> {
            Rec x = transactionsOf(second).get("POWER-L2");
            return x != null && "DEBITED".equals(x.str("status")) ? x : null;
        }).str("id");
        String pacs007 = Files.readString(WORKSPACE.resolve("tests/messages/pacs007-reversal.xml")).replace("CLEARING-DD-2026-10-07-001", "CLEARING-DD-L2")
                .replace("POWER-OCT", "POWER-L2").replace("SNDDD-", "SNDL2-").replace("REVERSAL-2026-10-09-001", "REVERSAL-L2");
        String reversal = upload(pacs007, "reversal.xml");
        Rec handled = await("the reversal to be handled", () -> {
            Rec m = quietGet("/api/messages/" + reversal, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
        assertEquals("PROCESSED", handled.str("status"), handled.toString());
        assertEquals("rails.sepa.SddReversalInbound", handled.str("channel"));
        assertEquals(1, Ops.num(handled.get("returnedCount")).intValue());
        assertEquals(1, Ops.list(handled.get("unmatched")).size(), "the collection we never received is reported, not ignored");
        Rec reversed = get("/api/transactions/" + collected, "operator1").body();
        assertEquals("RETURNED", reversed.str("status"));
        assertEquals("REVERSED", reversed.str("posting.status"));
        assertEquals("AM05", reversed.str("return.reasonCode"));
        assertEquals("the creditor's bank", reversed.str("return.requestedBy"));
        assertEquals(null, reversed.get("outboundId"), "nothing is sent back: the money comes to us");
        await("the customer to be told of the reversal", () -> told(collected).stream().anyMatch(x -> "DEBIT_REVERSAL".equals(x.str("kind"))));
        // the same reversal again finds nothing left to reverse
        String again = upload(pacs007.replace("REVERSAL-L2", "REVERSAL-L2B"), "reversal-again.xml");
        Rec repeated = await("the repeated reversal", () -> {
            Rec m = quietGet("/api/messages/" + again, "operator1");
            return "PROCESSED".equals(m.str("status")) ? m : null;
        });
        assertEquals(0, Ops.num(repeated.get("returnedCount")).intValue());
        assertTrue(repeated.get("unmatched").toString().contains("RETURNED, not debited"), repeated.toString());

        // ---- a recall carries the day by which the scheme wants an answer ----
        Map<String, Rec> incoming = incomingFile("D1");
        upload(withIds("camt056-recall.xml", "D1"), "recall-d1.xml");
        Rec waiting = await("the recall to wait", () -> {
            Rec x = quietGet("/api/transactions/" + incoming.get("RENT-D1").str("id"), "operator1");
            return "OPEN".equals(x.str("recall.status")) ? x : null;
        });
        String dueBy = waiting.str("recall.dueBy");
        assertNotNull(dueBy);
        long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(java.time.ZoneOffset.UTC), java.time.LocalDate.parse(dueBy));
        assertTrue(days >= 19 && days <= 25, "fifteen business days are about three weeks: " + dueBy);
    }

    @Test
    @Order(26)
    void instantPaymentsHaveTheirTimeAndACustomerChoosesWhatToBeTold() throws Exception {
        // ---- an instant payment that could not be finished in time is refused, never credited late ----
        String instant = Files.readString(WORKSPACE.resolve("tests/messages/pacs008-incoming-inst.xml")).replace("INST-IN-2026-10-06-0001", "INST-IN-SLOW-1")
                .replace("DINNER-SPLIT", "DINNER-SLOW").replace("INSTTX-1", "INSTTX-SLOW");
        try {
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.now().plusSeconds(60));
            upload(instant, "instant-slow.xml");
            Rec late = byReference("DINNER-SLOW", "RETURNED");
            assertEquals("AB05", late.str("return.reasonCode"));
            assertEquals(null, late.get("posting"), "nothing was credited");
            String refusal = get("/api/outbound/" + late.str("outboundId"), "operator1").body().str("payload");
            assertTrue(refusal.contains("<TxSts>RJCT</TxSts>") && refusal.contains("<Cd>AB05</Cd>") && refusal.contains("<OrgnlTxId>INSTTX-SLOW</OrgnlTxId>"), refusal);
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
        }

        // ---- an instant payment we sent and that got no answer in the time its rail allows is pointed out ----
        String silent = "ORVTXN-SILENT-1";
        store.insert(DocStore.TXN, Rec.of("id", silent, "status", "SENT", "endToEndId", "NO-ANSWER-1", "amount", new java.math.BigDecimal("15.00"), "currency", "EUR",
                "route", Rec.of("scheme", "SEPA_INST", "channel", "rails.sepa.SctInstOutbound"), "createdAt", java.time.Instant.now().minusSeconds(90).toString(),
                "sentAt", java.time.Instant.now().minusSeconds(80).toString(), "updatedAt", java.time.Instant.now().minusSeconds(80).toString()));
        String fresh = "ORVTXN-SILENT-2";
        store.insert(DocStore.TXN, Rec.of("id", fresh, "status", "SENT", "endToEndId", "NO-ANSWER-2", "amount", new java.math.BigDecimal("16.00"), "currency", "EUR",
                "route", Rec.of("scheme", "SEPA_INST", "channel", "rails.sepa.SctInstOutbound"), "createdAt", Platform.now(), "sentAt", Platform.now(), "updatedAt", Platform.now()));
        await("the unanswered payment to be marked", () -> Boolean.TRUE.equals(quietGet("/api/transactions/" + silent, "operator1").get("answerOverdue")));
        List<String> overdue = new ArrayList<>();
        for (Rec x : items(quietReply("/api/transactions?status=SENT&overdue=true"))) {
            overdue.add(x.str("id"));
        }
        assertTrue(overdue.contains(silent) && !overdue.contains(fresh), "only the one that waited longer than the rail allows: " + overdue);
        assertEquals("SENT", status(silent), "nothing is decided for it: only the answer decides");
        assertTrue(get("/api/transactions/" + silent, "operator1").body().get("events").toString().contains("ANSWER_OVERDUE"));
        store.delete(DocStore.TXN, silent);
        store.delete(DocStore.TXN, fresh);

        // ---- a customer who does not want notifications gets none; without the preference they come again ----
        String account = "DE08500105175400000042";
        assertEquals(403, send("PUT", "/api/x/notification-preferences/" + account, "viewer1", "application/json", "{\"notifications\": \"NONE\"}").status());
        assertEquals(422, send("PUT", "/api/x/notification-preferences/" + account, "checker1", "application/json", "{\"notifications\": \"SOMETIMES\"}").status());
        assertEquals(200, send("PUT", "/api/x/notification-preferences/" + account, "checker1", "application/json",
                "{\"notifications\": \"NONE\", \"note\": \"asked by phone\"}").status());
        upload(instant.replace("INST-IN-SLOW-1", "INST-IN-QUIET-1").replace("DINNER-SLOW", "DINNER-QUIET").replace("INSTTX-SLOW", "INSTTX-QUIET"), "instant-quiet.xml");
        String quiet = byReference("DINNER-QUIET", "CREDITED").str("id");
        Rec skipped = await("the notification to be left out", () -> {
            Rec x = quietGet("/api/transactions/" + quiet, "operator1");
            return "SKIPPED".equals(x.str("notify.state")) ? x : null;
        });
        assertTrue(skipped.get("events").toString().contains("NOT_NOTIFIED"), skipped.get("events").toString());
        assertEquals(List.of(), told(quiet));

        assertEquals(200, send("DELETE", "/api/x/notification-preferences/" + account, "checker1", "application/json", null).status());
        upload(instant.replace("INST-IN-SLOW-1", "INST-IN-LOUD-1").replace("DINNER-SLOW", "DINNER-LOUD").replace("INSTTX-SLOW", "INSTTX-LOUD"), "instant-loud.xml");
        String loud = byReference("DINNER-LOUD", "CREDITED").str("id");
        await("the customer to be told again", () -> told(loud).stream().anyMatch(x -> "CREDIT".equals(x.str("kind"))));
    }

    private static List<Rec> booked(String account, String day) {
        Reply reply = quietReply("/api/account-reports/entries?account=" + account + "&from=" + day + "&to=" + day);
        assertEquals(200, reply.status(), reply.body().toString());
        return items(reply);
    }

    @Test
    @Order(27)
    void aCustomerGetsAReportOfWhatWasBookedOnAnAccount() throws Exception {
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        // ---- a payment the customer sent is a debit on the account ----
        String sent = onePayment("REPORT-OUT-1", "Normal Supplier", "ZAR", "FIRNZAJJ", "4059900001");
        await("the payment to be debited", () -> "POSTED".equals(quietGet("/api/transactions/" + sent, "operator1").str("posting.status")));
        List<Rec> own = booked("4059900001", today);
        assertEquals(1, own.size(), own.toString());
        assertEquals("PAYMENT", own.get(0).str("kind"));
        assertEquals("DBIT", own.get(0).str("creditDebit"));
        assertEquals("Normal Supplier", own.get(0).str("counterparty"));
        assertEquals(List.of(), booked("4059900001", java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(3).toString()), "nothing was booked three days ago");

        // ---- an account that received payments today: credits, and a debit marked as a reversal for each one that went back ----
        String account = "DE08500105175400000042";
        List<Rec> entries = booked(account, today);
        assertTrue(entries.stream().anyMatch(e -> "DINNER-LOUD".equals(e.str("endToEndId")) && "CREDIT".equals(e.str("kind")) && "CRDT".equals(e.str("creditDebit"))), entries.toString());
        assertTrue(entries.stream().anyMatch(e -> "CREDIT_REVERSAL".equals(e.str("kind")) && "DBIT".equals(e.str("creditDebit"))), "refunds of earlier scenarios are there as reversals");
        assertTrue(entries.stream().noneMatch(e -> "DINNER-SLOW".equals(e.str("endToEndId"))), "a payment that was refused was never booked");
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).str("bookedAt").compareTo(entries.get(i).str("bookedAt")) <= 0, "oldest first");
        }

        // ---- the report is sent to the customer on request ----
        assertEquals(403, post("/api/account-reports", "viewer1", Rec.of("account", account, "from", today)).status());
        assertEquals(422, post("/api/account-reports", "operator1", Rec.of("account", account, "from", "2026-01-01", "to", "2026-06-01")).status());
        assertEquals(422, post("/api/account-reports", "operator1", Rec.of("account", account, "from", "yesterday")).status());
        Reply asked = post("/api/account-reports", "operator1", Rec.of("account", account, "from", today, "to", today));
        assertEquals(200, asked.status(), asked.body().toString());
        String id = asked.body().str("id");
        Rec report = await("the report to be sent", () -> {
            Rec o = quietGet("/api/outbound/" + id, "operator1");
            return "SENT".equals(o.str("status")) ? o : null;
        });
        assertEquals("accountReport", report.str("kind"));
        assertEquals("channels.CustomerAccountReportOutbound", report.str("channel"));
        assertEquals(entries.size(), Ops.num(report.get("entryCount")).intValue());
        String camt052 = report.str("payload");
        assertTrue(camt052.contains("camt.052.001.08") && camt052.contains("<IBAN>" + account + "</IBAN>") && camt052.contains("<EndToEndId>DINNER-LOUD</EndToEndId>")
                && camt052.contains("<FrDtTm>" + today + "T00:00:00Z</FrDtTm>") && camt052.contains("<RvslInd>true</RvslInd>") && !camt052.contains("<Bal>"), camt052);
        assertEquals(entries.size(), count(camt052, "<Ntry>"));
        assertEquals(String.valueOf(entries.size()), camt052.replaceAll("(?s).*<TtlNtries>\\s*<NbOfNtries>(\\d+)</NbOfNtries>.*", "$1"));
        // sending a report changes nothing on the payments it lists
        assertEquals("CREDITED", byReference("DINNER-LOUD", "CREDITED").str("status"));

        // ---- a customer who asked for a daily report gets the report of a day when the day is over, once ----
        assertEquals(422, send("PUT", "/api/x/notification-preferences/" + account, "checker1", "application/json", "{\"notifications\": \"ALL\", \"accountReport\": \"WEEKLY\"}").status());
        assertEquals(200, send("PUT", "/api/x/notification-preferences/" + account, "checker1", "application/json", "{\"notifications\": \"ALL\", \"accountReport\": \"DAILY\"}").status());
        String daily = "ORVRPT" + today.replace("-", "") + account;
        try {
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.now().plus(java.time.Duration.ofDays(1)));
            Rec written = await("the daily report", () -> {
                Reply r = quietReply("/api/outbound/" + daily);
                return r.status() == 200 && "SENT".equals(r.body().str("status")) ? r.body() : null;
            });
            assertTrue(written.str("payload").contains("<EndToEndId>DINNER-LOUD</EndToEndId>"), written.str("payload"));
            Thread.sleep(2500);
            assertEquals(1, items(quietReply("/api/outbound?kind=accountReport&q=" + daily)).size(), "written once");
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
            send("DELETE", "/api/x/notification-preferences/" + account, "checker1", "application/json", null);
        }
    }

    @Test
    @Order(28)
    void aCustomerWhoSendsMt101IsToldTheOutcomeByMt199() throws Exception {
        String mt101 = upload(Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")).replace("SUPPLIERS-1004", "SUPPLIERS-ST1")
                .replace("INV-88120", "INV-ST1").replace("INV-55071", "INV-ST2"), "suppliers-status.fin");
        await("the two transactions of the MT101", () -> transactionsOf(mt101).size() == 2);
        Rec report = await("the status messages", () -> {
            List<Rec> found = store.find(DocStore.OUTBOUND, Rec.of("instructionId", mt101, "kind", "statusReport"), "id", false, 10);
            return found.isEmpty() ? null : found.get(0);
        });
        assertEquals("channels.CorporateMtStatusOutbound", report.str("channel"));
        assertEquals("MT199", report.str("messageType"));
        String payload = report.str("payload").replace("\r", "");
        // one MT199 per transaction, addressed to the sender of the MT101, each quoting the transaction's own reference
        assertEquals(Ops.num(report.get("transactionCount")).intValue(), count(payload, "{2:I199KAROZAJJ"), payload);
        assertTrue(payload.contains(":21:INV-ST1") || payload.contains(":21:INV-ST2"), payload);
        assertTrue(payload.contains(":79:/ACCP/") || payload.contains(":79:/PDNG/") || payload.contains(":79:/RJCT/"), payload);
        assertTrue(payload.contains("YOUR MT101 SUPPLIERS-ST1"), payload);
        for (Object checked : Ops.list(post("/api/studio/check-message", "maker1", Rec.of("raw", report.str("payload"))).body().get("messages"))) {
            assertEquals(List.of(), Ops.list(((Rec) checked).get("problems")), checked.toString());
        }
        // what was told is kept on each transaction, so that a later change of status is told again
        await("the reported status to be kept", () -> transactionsOf(mt101).values().stream().anyMatch(x -> x.str("reportedStatus") != null));
    }

    @Test
    @Order(29)
    void theConsolesSessionIsACookieThatScriptsCannotReadAndOtherSitesCannotUse() throws Exception {
        HttpResponse<String> login = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Rec.of("username", "viewer1", "password", PASSWORDS.get("viewer1"), "session", "cookie")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        assertEquals(null, Json.parse(login.body()).get("token"), "the token is not in the answer, so no script ever holds it");
        String set = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(set.startsWith("orv_session=") && set.contains("HttpOnly") && set.contains("SameSite=Strict") && set.contains("Path=/api"), set);
        String cookie = set.substring(0, set.indexOf(';'));

        // reading works with the cookie alone
        HttpResponse<String> me = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        assertEquals("viewer1", Json.parse(me.body()).str("username"));
        // a change needs the header that only the Console's own script adds: a form on another site cannot send it
        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(base + "/api/auth/logout")).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> forged = HTTP.send(out.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, forged.statusCode(), forged.body());
        assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                "the forged request ended nothing");
        HttpResponse<String> left = HTTP.send(out.header("X-Orvanta-Console", "1").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, left.statusCode(), left.body());
        assertTrue(left.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"), "signing out takes the cookie away");
        // and the token it held is worth nothing any more
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        TOKENS.remove("viewer1");
        // a system that signs in without asking for a cookie still gets its token and uses it as before
        assertEquals(200, get("/api/me", "viewer1").status());
    }

    private static HttpResponse<String> signIn(String user, String code) throws Exception {
        Rec body = Rec.of("username", user, "password", PASSWORDS.get(user));
        if (code != null) {
            body.put("code", code);
        }
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String codeNow(String secret, int stepsFromNow) {
        return Crypto.totp(secret, System.currentTimeMillis() / 1000 / 30 + stepsFromNow);
    }

    @Test
    @Order(30)
    void aUserTurnsOnASecondStepAndThenSignsInWithPasswordAndCode() throws Exception {
        // the algorithm is the one authenticator apps use: the published test value of RFC 6238 for SHA-1
        assertEquals("287082", Crypto.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59 / 30));

        assertEquals(false, get("/api/me", "viewer1").body().at("mfa.enabled"));
        Rec enrolment = post("/api/me/mfa/enroll", "viewer1", null).body();
        String secret = enrolment.str("secret");
        assertTrue(secret.matches("[A-Z2-7]{32}") && enrolment.str("uri").startsWith("otpauth://totp/Orvanta:viewer1?secret=" + secret), enrolment.toString());
        // nothing is on until a code from that secret is shown
        assertEquals(422, post("/api/me/mfa/confirm", "viewer1", Rec.of("code", "000000")).status());
        assertEquals(200, signIn("viewer1", null).statusCode(), "still password only");
        Reply confirmed = post("/api/me/mfa/confirm", "viewer1", Rec.of("code", codeNow(secret, 0)));
        assertEquals(200, confirmed.status());
        List<?> recoveryCodes = Ops.list(confirmed.body().get("recoveryCodes"));
        assertEquals(8, recoveryCodes.size());
        assertTrue(recoveryCodes.stream().allMatch(c -> String.valueOf(c).matches("[a-z0-9]{4}-[a-z0-9]{4}")), recoveryCodes.toString());
        assertEquals(8, Ops.num(get("/api/me", "viewer1").body().at("mfa.recoveryCodesLeft")).intValue());
        assertEquals(true, get("/api/me", "viewer1").body().at("mfa.enabled"));
        assertEquals(409, post("/api/me/mfa/enroll", "viewer1", null).status(), "a second secret cannot be issued over the first");

        // now the password alone starts no session
        HttpResponse<String> passwordOnly = signIn("viewer1", null);
        assertEquals(401, passwordOnly.statusCode());
        assertEquals(true, Json.parse(passwordOnly.body()).get("mfaRequired"));
        assertEquals(null, Json.parse(passwordOnly.body()).get("token"));
        assertEquals(401, signIn("viewer1", "123456").statusCode(), "a wrong code is refused like a wrong password");
        // the code that confirmed the set-up was used; the next one signs in, and only once
        String next = codeNow(secret, 1);
        HttpResponse<String> withCode = signIn("viewer1", next);
        assertEquals(200, withCode.statusCode(), withCode.body());
        assertNotNull(Json.parse(withCode.body()).str("token"));
        assertEquals(401, signIn("viewer1", next).statusCode(), "a code is taken once");

        // without the device: a recovery code signs in, once, and says how many are left
        HttpResponse<String> rescued = signIn("viewer1", String.valueOf(recoveryCodes.get(2)).toUpperCase(java.util.Locale.ROOT));
        assertEquals(200, rescued.statusCode(), rescued.body());
        assertEquals(true, Json.parse(rescued.body()).get("recoveryCodeUsed"));
        assertEquals(7, Ops.num(Json.parse(rescued.body()).get("recoveryCodesLeft")).intValue());
        assertEquals(401, signIn("viewer1", String.valueOf(recoveryCodes.get(2))).statusCode(), "a recovery code works once");
        assertEquals(401, signIn("viewer1", "zzzz-zzzz").statusCode());
        assertTrue(items(get("/api/security/events?type=LOGIN_OK", "maker1")).stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("recovery code")));

        // the secret never leaves the server again
        String users = Json.write(get("/api/users", "maker1").body());
        assertTrue(!users.contains(secret) && !users.contains("\"secret\""), "the user list shows only whether the second step is on");
        Rec listed = null;
        for (Rec u : items(get("/api/users", "maker1"))) {
            listed = "viewer1".equals(u.str("id")) ? u : listed;
        }
        assertEquals(true, listed.at("mfa.enabled"));

        // a user who lost the device is helped by an administrator, with a second person
        approve(post("/api/users", "maker1", Rec.of("username", "viewer1", "displayName", "viewer1", "roles", List.of(), "status", "ACTIVE", "resetMfa", true)));
        TOKENS.remove("viewer1");
        assertEquals(200, signIn("viewer1", null).statusCode(), "password only again, until it is set up anew");
        assertEquals(false, get("/api/me", "viewer1").body().at("mfa.enabled"));

        // set up anew: new recovery codes come with it, and a new set is given against a current code; the old set is void
        String secret2 = post("/api/me/mfa/enroll", "viewer1", null).body().str("secret");
        List<?> codes2 = Ops.list(post("/api/me/mfa/confirm", "viewer1", Rec.of("code", codeNow(secret2, 0))).body().get("recoveryCodes"));
        assertEquals(8, codes2.size());
        assertEquals(422, post("/api/me/mfa/recovery", "viewer1", Rec.of("code", "000000")).status());
        Reply renewed = post("/api/me/mfa/recovery", "viewer1", Rec.of("code", codeNow(secret2, 1)));
        assertEquals(200, renewed.status(), renewed.body().toString());
        List<?> newCodes = Ops.list(renewed.body().get("recoveryCodes"));
        assertEquals(8, newCodes.size());
        assertEquals(401, signIn("viewer1", String.valueOf(codes2.get(3))).statusCode(), "an old recovery code is void");
        assertEquals(200, signIn("viewer1", String.valueOf(newCodes.get(0))).statusCode());
        assertTrue(!Json.write(store.get(DocStore.USER, "viewer1")).contains(String.valueOf(newCodes.get(1))), "only hashes of the codes are kept");
        // and off again for the scenarios that follow, by an administrator
        approve(post("/api/users", "maker1", Rec.of("username", "viewer1", "displayName", "viewer1", "roles", List.of(), "status", "ACTIVE", "resetMfa", true)));
        TOKENS.remove("viewer1");
        assertEquals(200, signIn("viewer1", null).statusCode());
    }

    @Test
    @Order(31)
    void aMonitoringSystemScrapesNumbersNotPaymentData() throws Exception {
        HttpResponse<String> scraped = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, scraped.statusCode());
        assertTrue(scraped.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"), scraped.headers().map().toString());
        String text = scraped.body();
        assertTrue(text.contains("orvanta_up 1") && text.contains("orvanta_transactions{status=\"ACCEPTED\"} ") && text.contains("orvanta_approvals_pending ")
                && text.contains("orvanta_http_requests_total{route=\"/api/transactions\",status=\"2xx\"} ")
                && text.contains("orvanta_http_request_seconds_bucket{route=\"/api/transactions/{id}\",le=\"+Inf\"} ") && text.contains("process_memory_used_bytes "), text);
        // the overdue answer and the locked user of earlier scenarios are numbers here, nothing more
        assertTrue(text.matches("(?s).*orvanta_answers_overdue \\d+.*") && text.matches("(?s).*orvanta_users_locked \\d+.*"), text);
        assertTrue(!text.contains("ORVTXN") && !text.contains("DE08500105175400000042") && !text.contains("Karoo"), "no ids, accounts or names");
        // the metric name carries the route pattern, never the id of a payment
        assertTrue(!text.matches("(?s).*route=\"/api/transactions/ORV.*"), text);
    }

    @Test
    @Order(32)
    void aListIsExportedAsASpreadsheetFileWithTheSameFiltersAndTheExportIsLogged() throws Exception {
        HttpResponse<String> file = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/transactions?status=ACCEPTED&format=csv"))
                .header("Authorization", "Bearer " + token("operator1")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, file.statusCode(), file.body());
        assertTrue(file.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertTrue(file.headers().firstValue("Content-Disposition").orElse("").contains("orvanta-api-transactions-"), file.headers().map().toString());
        String[] lines = file.body().replace("﻿", "").split("\r\n");
        List<String> header = List.of(lines[0].split(","));
        assertTrue(header.contains("id") && header.contains("status") && header.contains("debtor.name") && header.contains("amount"), lines[0]);
        assertTrue(!header.contains("events") && !header.contains("payload"), lines[0]);
        int accepted = Ops.num(get("/api/transactions?status=ACCEPTED&limit=1", "operator1").body().get("total")).intValue();
        assertEquals(accepted, lines.length - 1, "every row of the filtered list, not one page of it");
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].contains(",ACCEPTED,") || lines[i].startsWith("ACCEPTED,") || lines[i].endsWith(",ACCEPTED"), lines[i]);
        }
        // the whole list, unfiltered, is the same file without the filter
        HttpResponse<String> all = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/transactions?format=csv"))
                .header("Authorization", "Bearer " + token("operator1")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(all.body().split("\r\n").length - 1 > accepted, "more rows without the status filter");
        // taking data out is written to the security log
        List<Rec> events = items(get("/api/security/events?type=EXPORT", "maker1"));
        assertTrue(events.stream().anyMatch(e -> "operator1".equals(e.str("username")) && String.valueOf(e.str("detail")).contains("api-transactions")), events.toString());
        // a cell that a spreadsheet would run as a formula is neutralised
        assertEquals("\"'=SUM(A1)\"", ApiServer.class.getDeclaredMethod("csvCell", String.class) == null ? null : csvCell("=SUM(A1)"));
    }

    private static String csvCell(String value) throws Exception {
        java.lang.reflect.Method m = ApiServer.class.getDeclaredMethod("csvCell", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    @Test
    @Order(33)
    void aUserWithoutTheRightToReadDataInFullSeesAccountsAndNamesMasked() throws Exception {
        // a role of its own: sees payments, but not whose they are
        approve(post("/api/roles", "maker1", Rec.of("id", "AUDITOR", "description", "Follows payments without reading personal data", "permissions", List.of("payments.view"))));
        String password = "masked-Auditor-pass-77";
        approve(post("/api/users", "maker1", Rec.of("username", "auditor2", "displayName", "Masked Auditor", "roles", List.of("AUDITOR"), "status", "ACTIVE", "password", password)));
        signInNew("auditor2", password);

        Rec full = firstOf(get("/api/transactions?q=Mokoena", "operator1").body(), "items");
        Rec seen = get("/api/transactions/" + full.str("id"), "auditor2").body();
        assertEquals(full.str("id"), seen.str("id"));
        assertEquals(full.str("status"), seen.str("status"));
        assertEquals(full.get("amount").toString(), seen.get("amount").toString(), "amounts are not personal data");
        String account = full.str("debtor.account");
        assertTrue(seen.str("debtor.account").endsWith(account.substring(account.length() - 4)) && seen.str("debtor.account").startsWith("*"), seen.str("debtor.account"));
        assertTrue(!seen.str("debtor.account").equals(account));
        assertTrue(seen.str("creditor.name").matches("[A-Za-z]\\.( [A-Za-z]\\.)?"), seen.str("creditor.name"));
        assertEquals(seen.get("remittance") == null ? null : "[hidden]", seen.get("remittance"));
        // the list, the search and the export are masked the same way; the search still finds by the full value on the server
        Rec listed = firstOf(get("/api/transactions?q=Mokoena", "auditor2").body(), "items");
        assertEquals(full.str("id"), listed.str("id"));
        assertTrue(listed.str("creditor.name").endsWith("."), listed.str("creditor.name"));
        HttpResponse<String> file = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/transactions?q=Mokoena&format=csv"))
                .header("Authorization", "Bearer " + token("auditor2")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(!file.body().contains(account) && !file.body().contains("Mokoena"), file.body());
        // an operator reads everything
        assertEquals(account, get("/api/transactions/" + full.str("id"), "operator1").body().str("debtor.account"));
        assertTrue(Ops.list(get("/api/roles", "maker1").body().get("permissions")).contains("data.unmasked"));
    }

    @Test
    @Order(34)
    void operatorsPauseAChannelResendAFileCancelAWholeInstructionAndDecideOnPossibleDuplicates() throws Exception {
        // ---- a channel is paused: its files wait as created; resumed, they go ----
        String channel = "channels.ZaRtcOutbound";
        approve(post("/api/channels/" + channel + "/pause", "operator1", Rec.of("comment", "the clearing asked us to hold")));
        assertEquals(409, post("/api/channels/" + channel + "/pause", "operator1", null).status(), "paused already");
        assertTrue(items(get("/api/channels/paused", "operator1")).stream().anyMatch(x -> channel.equals(x.str("channel"))));
        String held = onePayment("PAUSED-1", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        Rec waiting = await("the file to be created", () -> {
            Rec x = quietGet("/api/transactions/" + held, "operator1");
            return x.str("outboundId") != null ? quietGet("/api/outbound/" + x.str("outboundId"), "operator1") : null;
        });
        Thread.sleep(3000);
        assertEquals("CREATED", quietGet("/api/outbound/" + waiting.str("id"), "operator1").str("status"), "nothing leaves a paused channel");
        approve(post("/api/channels/" + channel + "/resume", "operator1", Rec.of("comment", "cleared")));
        try {
            await("the file to go", () -> List.of("SENT", "ACKNOWLEDGED").contains(quietGet("/api/outbound/" + waiting.str("id"), "operator1").str("status")));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + ": outbound=" + quietGet("/api/outbound/" + waiting.str("id"), "operator1")
                    + " setting=" + store.get(DocStore.SETTING, "channel.paused." + channel) + " paused=" + items(quietReply("/api/channels/paused")), e);
        }
        assertEquals(List.of(), items(get("/api/channels/paused", "operator1")));

        // ---- a sent file is sent again when the partner lost it, with a second person ----
        Rec resent = approve(post("/api/outbound/" + waiting.str("id") + "/resend", "operator1", Rec.of("comment", "the clearing did not get it")));
        assertEquals("RESENT", resent.at("result.outcome"), resent.toString());
        Rec again = get("/api/outbound/" + waiting.str("id"), "operator1").body();
        assertTrue(List.of("SENT", "ACKNOWLEDGED").contains(again.str("status")), again.str("status"));
        assertTrue(again.get("events").toString().contains("RESENT"), again.get("events").toString());

        // ---- the red button: every payment of a file that can still be cancelled ----
        String salaries = upload(Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-RED-1"), "salaries-red.xml");
        await("the salaries to be settled one way or the other", () -> transactionsOf(salaries).size() >= 2 && transactionsOf(salaries).values().stream()
                .allMatch(x -> "ACCEPTED".equals(x.str("status")) || String.valueOf(x.str("status")).startsWith("REJECTED")));
        long accepted = transactionsOf(salaries).values().stream().filter(x -> "ACCEPTED".equals(x.str("status"))).count();
        assertTrue(accepted > 0, transactionsOf(salaries).toString());
        assertEquals(403, post("/api/messages/" + salaries + "/cancel", "viewer1", Rec.of("reasonText", "x")).status());
        Rec decided = approve(post("/api/messages/" + salaries + "/cancel", "operator1", Rec.of("reasonCode", "CUST", "reasonText", "Wrong file sent", "comment", "the customer called")));
        assertEquals(accepted, Ops.num(decided.at("result.cancelled")).longValue(), "every accepted payment of the file, and only those: " + decided);
        assertEquals(0, Ops.num(decided.at("result.refused")).intValue());
        await("every accepted payment of the file to be cancelled or asked to be", () -> transactionsOf(salaries).values().stream()
                .noneMatch(x -> "ACCEPTED".equals(x.str("status"))));

        // ---- a possible duplicate on a channel with 'duplicates: HOLD' waits for a person ----
        // the duplicate check runs where a processing flow includes it: the SEPA rail does, so a SEPA file is used
        String sepa = Files.readString(WORKSPACE.resolve("tests/messages/pain001-sepa.xml")).replace("SEPA-2026-10-001", "SEPA-DUP-A").replace("SEPA-000", "DUP-000");
        String first = upload(sepa, "sepa-dup-a.xml");
        await("the first file's payments to be past the duplicate check", () -> transactionsOf(first).size() >= 5
                && transactionsOf(first).values().stream().noneMatch(x -> List.of("STAGED", "CREATED", "PROCESSING").contains(x.str("status"))));
        // the same payments in a second file (another file reference, so it is not the same file, which ingest refuses outright)
        String second = upload(sepa.replace("SEPA-DUP-A", "SEPA-DUP-B"), "sepa-dup-b.xml");
        Rec copy = await("the second file's payments to be held", () -> {
            Rec x = transactionsOf(second).get("DUP-0001");
            return x != null && "HELD".equals(x.str("status")) ? quietGet("/api/transactions/" + x.str("id"), "operator1") : null;
        });
        assertEquals("DUPL", copy.str("hold.code"), copy.toString());
        // a person confirms one as a duplicate: rejected; and releases another as a payment in its own right: it goes on, the override recorded
        Rec other = await("the second held payment", () -> {
            Rec x = transactionsOf(second).get("DUP-0002");
            return x != null && "HELD".equals(x.str("status")) ? x : null;
        });
        approve(post("/api/transactions/" + copy.str("id") + "/reject", "operator1", Rec.of("comment", "sent twice")));
        approve(post("/api/transactions/" + other.str("id") + "/release", "operator1", Rec.of("comment", "a second invoice with the same amount")));
        await("the confirmed duplicate to be rejected", () -> "REJECTED_BY_APPLICATION".equals(status(copy.str("id"))));
        Rec released = await("the released one to go on", () -> {
            Rec x = quietGet("/api/transactions/" + other.str("id"), "operator1");
            return !"HELD".equals(x.str("status")) ? x : null;
        });
        assertTrue(Ops.list(released.get("overrides")).contains("DUPL"), released.toString());
        assertTrue(!String.valueOf(released.str("status")).startsWith("REJECTED"), released.toString());
    }

    @Test
    @Order(35)
    void aPriorityPaymentGoesAtOnceAndAnOperatorExtendsTodaysCutOff() throws Exception {
        // ---- HIGH priority: the payment does not wait for its bulk to fill or age ----
        String raw = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>PRIORITY-1</MsgId>"
                + "<CreDtTm>2026-10-04T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>PRIORITY-1-1</PmtInfId>"
                + "<PmtTpInf><InstrPrty>HIGH</InstrPrty></PmtTpInf><ReqdExctnDt><Dt>2026-10-05</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>4051122334</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>PRIORITY-1</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"ZAR\">900.00</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>FIRNZAJJ</BICFI></FinInstnId></CdtrAgt>"
                + "<Cdtr><Nm>Normal Supplier</Nm></Cdtr><CdtrAcct><Id><Othr><Id>62011223344</Id></Othr></Id></CdtrAcct>"
                + "</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
        String instruction = upload(raw, "priority.xml");
        Rec urgent = await("the priority payment", () -> transactionsOf(instruction).get("PRIORITY-1"));
        assertEquals("HIGH", urgent.str("priority"));
        Rec sent = await("the priority payment to be in a file", () -> {
            Rec x = quietGet("/api/transactions/" + urgent.str("id"), "operator1");
            return x.str("outboundId") != null ? quietGet("/api/outbound/" + x.str("outboundId"), "operator1") : null;
        });
        assertEquals(1, Ops.num(sent.get("transactionCount")).intValue(), "the bulk closed for it alone, instead of waiting for others");

        // ---- an operator moves today's SEPA cut-off later, with a second person; the rule checks the request ----
        assertEquals(422, send("PUT", "/api/x/cutoff-extensions/NO-SUCH-SCHEME", "checker1", "application/json", "{\"date\": \"2026-10-06\", \"until\": \"17:30\"}").status());
        assertEquals(422, send("PUT", "/api/x/cutoff-extensions/SEPA-SCT", "checker1", "application/json", "{\"date\": \"2026-10-06\", \"until\": \"14:00\"}").status(), "not later than the usual 15:00");
        Reply extended = send("PUT", "/api/x/cutoff-extensions/SEPA-SCT", "checker1", "application/json", "{\"date\": \"2026-10-06\", \"until\": \"17:30\", \"note\": \"announced by the clearing\"}");
        assertEquals(200, extended.status(), extended.body().toString());
        assertEquals("17:30", extended.body().str("until"));
        assertTrue(items(get("/api/datasets", "operator1")).stream().anyMatch(d -> "data.CutOffExtensions".equals(d.str("name"))), "offered under Customer instructions");
        assertEquals(200, send("DELETE", "/api/x/cutoff-extensions/SEPA-SCT", "checker1", "application/json", null).status());
    }

    @Test
    @Order(36)
    void anUnansweredPaymentIsAskedAboutAndAnEnquiryFromAnotherBankIsAnswered() throws Exception {
        // ---- out: a sent instant payment with no answer in time; the rail is asked once with a pacs.028 ----
        String silent = "ORVTXN-SILENT-ENQ";
        store.insert(DocStore.TXN, Rec.of("id", silent, "status", "SENT", "endToEndId", "NO-ANSWER-ENQ", "amount", new java.math.BigDecimal("15.00"), "currency", "EUR",
                "uetr", "11111111-2222-4333-8444-555555555555", "outboundId", "ORVOUT-SILENT-FILE", "creditor", Rec.of("name", "Camille Laurent", "agentBic", "BNPAFRPP"),
                "route", Rec.of("scheme", "SEPA_INST", "channel", "rails.sepa.SctInstOutbound"), "createdAt", java.time.Instant.now().minusSeconds(90).toString(),
                "sentAt", java.time.Instant.now().minusSeconds(80).toString(), "updatedAt", java.time.Instant.now().minusSeconds(80).toString()));
        Rec asked = await("the rail to be asked", () -> {
            Rec x = quietGet("/api/transactions/" + silent, "operator1");
            return x.at("enquiry.outboundId") != null ? x : null;
        });
        Rec enquiry = get("/api/outbound/" + asked.str("enquiry.outboundId"), "operator1").body();
        assertEquals("statusEnquiry", enquiry.str("kind"));
        assertEquals("rails.sepa.StatusEnquiryOutbound", enquiry.str("channel"));
        String pacs028 = enquiry.str("payload");
        assertTrue(pacs028.contains("pacs.028.001.03") && pacs028.contains("<OrgnlMsgId>ORVOUT-SILENT-FILE</OrgnlMsgId>") && pacs028.contains("<OrgnlTxId>" + silent + "</OrgnlTxId>")
                && pacs028.contains("<OrgnlUETR>11111111-2222-4333-8444-555555555555</OrgnlUETR>") && pacs028.contains("<BICFI>BNPAFRPP</BICFI>"), pacs028);
        assertTrue(asked.get("events").toString().contains("STATUS_ENQUIRED"));
        Thread.sleep(2500);
        assertEquals(asked.str("enquiry.outboundId"), get("/api/transactions/" + silent, "operator1").body().str("enquiry.outboundId"), "asked once, not at every sweep");
        assertEquals("SENT", status(silent), "the enquiry decides nothing");
        store.delete(DocStore.TXN, silent);

        // ---- in: another bank asks about a payment it sent us, and about one that never arrived ----
        Rec credited = byReference("DINNER-LOUD", "CREDITED");
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pacs028-enquiry.xml")).replace("INST-IN-2026-10-06-0001", credited.str("originalMsgId"))
                .replace("DINNER-SPLIT", "DINNER-LOUD").replace("INSTTX-1", credited.str("originalTxId"));
        String message = upload(raw, "enquiry.xml");
        Rec handled = await("the enquiry to be answered", () -> {
            Rec m = quietGet("/api/messages/" + message, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
        assertEquals("PROCESSED", handled.str("status"), handled.toString());
        assertEquals("rails.sepa.StatusEnquiryInbound", handled.str("channel"));
        assertEquals(1, Ops.num(handled.get("answeredCount")).intValue());
        assertEquals(1, Ops.num(handled.get("unknownCount")).intValue());
        List<?> answers = Ops.list(handled.get("answers"));
        Rec known = get("/api/outbound/" + ((Rec) answers.get(0)).str("outboundId"), "operator1").body();
        assertEquals("statusAnswer", known.str("kind"));
        assertTrue(known.str("payload").contains("<TxSts>ACCP</TxSts>") && known.str("payload").contains("<OrgnlEndToEndId>DINNER-LOUD</OrgnlEndToEndId>"), known.str("payload"));
        Rec none = get("/api/outbound/" + ((Rec) answers.get(1)).str("outboundId"), "operator1").body();
        assertTrue(none.str("payload").contains("<TxSts>RJCT</TxSts>") && none.str("payload").contains("<Cd>NOOR</Cd>") && none.str("payload").contains("<OrgnlTxId>LOSTTX-1</OrgnlTxId>"), none.str("payload"));
        assertTrue(get("/api/transactions/" + credited.str("id"), "operator1").body().get("events").toString().contains("STATUS_ASKED"));
    }

    @Test
    @Order(37)
    void theSwiftNetworksAckMarksAMessageDeliveredAndItsNakPutsThePaymentInRepair() throws Exception {
        String mt101 = upload(Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")).replace("SUPPLIERS-1004", "SUPPLIERS-ACK1")
                .replace("INV-88120", "INV-ACK1").replace("INV-55071", "INV-ACK2"), "suppliers-ack.fin");
        Map<String, Rec> sent = await("both payments to be sent", () -> transactionsOf(mt101).size() == 2
                && transactionsOf(mt101).values().stream().allMatch(x -> List.of("SENT", "ACCEPTED").contains(x.str("status"))) ? transactionsOf(mt101) : null);
        String acked = sent.get("INV-ACK1").str("id");
        String naked = sent.get("INV-ACK2").str("id");
        String acks = Files.readString(WORKSPACE.resolve("tests/messages/swift-acks.fin")).replace("ORVTXN0000000031", acked).replace("ORVTXN0000000032", naked);
        Reply received = send("POST", "/api/inbound?fileName=acks.fin", "operator1", "text/plain", acks);
        assertEquals(200, received.status(), received.body().toString());
        List<?> messages = Ops.list(received.body().get("messages"));
        assertEquals(2, messages.size(), "an acknowledgement and its copy are one thing, not two: " + received.body());
        for (Object m : messages) {
            assertEquals("rails.swift.SwiftDeliveryInbound", ((Rec) m).str("channel"), m.toString());
        }
        // ACK: recorded, nothing else changes
        Rec delivered = await("the ACK to be recorded", () -> {
            Rec x = quietGet("/api/transactions/" + acked, "operator1");
            return "ACKED".equals(x.str("delivery.status")) ? x : null;
        });
        assertTrue(List.of("SENT", "ACCEPTED").contains(delivered.str("status")));
        assertTrue(delivered.get("events").toString().contains("DELIVERED"));
        // NAK: the message never left; the payment is for an operator, with the network's code
        Rec refused = await("the NAK to park the payment", () -> {
            Rec x = quietGet("/api/transactions/" + naked, "operator1");
            return "NAKED".equals(x.str("delivery.status")) ? x : null;
        });
        assertEquals("T13", refused.str("delivery.code"));
        assertTrue("REPAIR".equals(refused.str("status")) || "ACCEPTED".equals(refused.str("status")), refused.str("status"));
        if ("REPAIR".equals(refused.str("status"))) {
            assertEquals("NAK", refused.str("reasonCode"));
            assertTrue(refused.str("reasonText").contains("T13"), refused.str("reasonText"));
        }
        // an acknowledgement for a message we never sent is kept, matched to nothing
        String stray = upload(acks.substring(0, acks.indexOf("{1:F21", 10)).replace(acked, "ORVTXN-NOT-OURS"), "stray-ack.fin");
        Rec kept = await("the stray acknowledgement", () -> {
            Rec m = quietGet("/api/messages/" + stray, "operator1");
            return "PROCESSED".equals(m.str("status")) ? m : null;
        });
        assertEquals(false, kept.get("matched"));
    }

    @Test
    @Order(38)
    void theFundsOfASanctionsHitAreFrozenAndFollowThePersonsDecision() throws Exception {
        // a file with two payments from sanctioned senders (the sample has one; a second sender is made one): one is released, one returned
        String file = upload(withIds("pacs008-incoming.xml", "FZ").replace("Camille Laurent", "BLOCKED PERSON"), "frozen.xml");
        Map<String, Rec> held = await("the payments of the sanctioned senders to be held", () -> {
            Map<String, Rec> hits = new HashMap<>();
            for (Rec x : transactionsOf(file).values()) {
                if (String.valueOf(x.str("debtor.name")).contains("BLOCKED")) {
                    hits.put(x.str("endToEndId"), x);
                }
            }
            return hits.size() == 2 && hits.values().stream().allMatch(x -> "HELD".equals(x.str("status"))) ? hits : null;
        });
        Rec first = get("/api/transactions/" + held.values().iterator().next().str("id"), "operator1").body();
        assertEquals("SANC", first.str("hold.code"));
        assertEquals("POSTED", first.str("frozen.status"), "the funds are on the seized-funds account");
        assertEquals("9700000001", first.str("frozen.creditAccount"));
        assertEquals(null, first.get("posting"), "the customer has nothing yet");
        List<Rec> two = new ArrayList<>(held.values());

        // released: the funds come off the seized-funds account and the customer is credited
        approve(post("/api/transactions/" + two.get(0).str("id") + "/release", "operator1", Rec.of("comment", "a namesake, not the listed person")));
        Rec credited = await("the released payment to be credited", () -> {
            Rec x = quietGet("/api/transactions/" + two.get(0).str("id"), "operator1");
            return "CREDITED".equals(x.str("status")) ? x : null;
        });
        assertEquals("POSTED", credited.str("posting.status"));
        assertNotNull(credited.at("frozen.reversal"), "the frozen posting was reversed before the credit");
        assertEquals(credited.str("creditor.account"), credited.str("posting.creditAccount"));

        // rejected: the funds come off the seized-funds account and the payment goes back
        approve(post("/api/transactions/" + two.get(1).str("id") + "/reject", "operator1", Rec.of("comment", "the listed person")));
        Rec returned = await("the rejected payment to be sent back", () -> {
            Rec x = quietGet("/api/transactions/" + two.get(1).str("id"), "operator1");
            return "RETURNED".equals(x.str("status")) ? x : null;
        });
        assertEquals("REVERSED", returned.str("frozen.status"));
        assertTrue(returned.get("events").toString().contains("FUNDS_UNFROZEN"), returned.get("events").toString());
        assertEquals("SANC", returned.str("return.reasonCode"));
    }

    @Test
    @Order(39)
    void investigationsFromOtherBanksAreCasesOnThePaymentAnsweredByAPerson() throws Exception {
        // ---- a request for information about a payment we sent ----
        String ours = onePayment("RFI-PAY-1", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        await("the payment to be sent", () -> List.of("SENT", "ACCEPTED").contains(status(ours)));
        String rfi = Files.readString(WORKSPACE.resolve("tests/messages/camt026-rfi.xml")).replace("ORVTXN0000000001", ours).replace("INV-RFI-1", "RFI-PAY-1");
        String asked = upload(rfi, "rfi.xml");
        Rec opened = await("the case to be opened", () -> {
            Rec m = quietGet("/api/messages/" + asked, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
        assertEquals("PROCESSED", opened.str("status"), opened.toString());
        assertEquals("rails.sepa.InvestigationInbound", opened.str("channel"));
        assertEquals(true, opened.get("matched"));
        Rec txn = get("/api/transactions/" + ours, "operator1").body();
        Rec open = (Rec) Ops.list(txn.get("investigations")).get(0);
        assertEquals("RFI", open.str("kind"));
        assertEquals("CASE-RFI-1", open.str("caseId"));
        assertEquals("OPEN", open.str("status"));
        assertTrue(open.str("text").contains("full address"), open.str("text"));
        assertTrue(items(get("/api/transactions?investigation=true", "operator1")).stream().anyMatch(x -> ours.equals(x.str("id"))), "in the review queue's list");
        // a person answers; a second person approves; the answer goes out as camt.028
        assertEquals(422, post("/api/transactions/" + ours + "/investigations/CASE-RFI-1/answer", "operator1", Rec.of("text", "")).status(), "information needs text");
        assertEquals(409, post("/api/transactions/" + ours + "/investigations/NO-SUCH-CASE/answer", "operator1", Rec.of("text", "x")).status());
        Rec answered = approve(post("/api/transactions/" + ours + "/investigations/CASE-RFI-1/answer", "operator1",
                Rec.of("text", "Debtor address: 12 Mine Road, Kimberley 8301, South Africa", "comment", "from the account record")));
        String outboundId = answered.str("result.outboundId");
        Rec info = get("/api/outbound/" + outboundId, "operator1").body();
        assertEquals("investigationAnswer", info.str("kind"));
        assertTrue(info.str("payload").contains("camt.028.001.09") && info.str("payload").contains("<Id>CASE-RFI-1</Id>") && info.str("payload").contains("12 Mine Road")
                && info.str("payload").contains("<OrgnlEndToEndId>RFI-PAY-1</OrgnlEndToEndId>"), info.str("payload"));
        Rec after = get("/api/transactions/" + ours, "operator1").body();
        assertEquals("ANSWERED", ((Rec) Ops.list(after.get("investigations")).get(0)).str("status"));
        assertEquals(false, after.get("investigationOpen"));
        assertEquals(409, post("/api/transactions/" + ours + "/investigations/CASE-RFI-1/answer", "operator1", Rec.of("text", "again")).status(), "answered once");

        // ---- a claim of non-receipt about a payment we received and credited: answered IPAY with the day it was processed ----
        Rec credited = byReference("DINNER-LOUD", "CREDITED");
        String claim = Files.readString(WORKSPACE.resolve("tests/messages/camt027-claim.xml")).replace("CLEARING-IN-2026-10-06-001", credited.str("originalMsgId"))
                .replace("RENT-OCT", "DINNER-LOUD").replace("SNDTX-1", credited.str("originalTxId"));
        String claimed = upload(claim, "claim.xml");
        await("the claim to be opened", () -> "PROCESSED".equals(quietGet("/api/messages/" + claimed, "operator1").str("status")));
        assertEquals(422, post("/api/transactions/" + credited.str("id") + "/investigations/CASE-CNR-1/answer", "operator1", Rec.of("text", "x")).status(), "a resolution needs a code");
        Rec resolved = approve(post("/api/transactions/" + credited.str("id") + "/investigations/CASE-CNR-1/answer", "operator1",
                Rec.of("confirmation", "IPAY", "text", "credited on receipt", "comment", "checked the posting")));
        Rec resolution = get("/api/outbound/" + resolved.str("result.outboundId"), "operator1").body();
        assertTrue(resolution.str("payload").contains("camt.029.001.09") && resolution.str("payload").contains("<Conf>IPAY</Conf>")
                && resolution.str("payload").contains("<Id>CASE-CNR-1</Id>") && resolution.str("payload").contains("<DtPrcd>"), resolution.str("payload"));

        // ---- a claim about a payment nobody here knows is answered at once with RJNR ----
        String unknown = upload(claim.replace("CASE-CNR-1", "CASE-CNR-2").replace("CNR-2026-10-06-001", "CNR-2").replace(credited.str("originalTxId"), "NO-SUCH-TX").replace("DINNER-LOUD", "NO-SUCH-E2E"), "claim-unknown.xml");
        Rec none = await("the unknown claim to be answered", () -> {
            Rec m = quietGet("/api/messages/" + unknown, "operator1");
            return "PROCESSED".equals(m.str("status")) ? m : null;
        });
        assertEquals(false, none.get("matched"));
        assertTrue(get("/api/outbound/" + none.str("answerId"), "operator1").body().str("payload").contains("<Conf>RJNR</Conf>"));
    }

    @Test
    @Order(40)
    void aPersonMatchesAStatementEntryTheEngineCouldNotAndTheDayIsReportedPerChannel() throws Exception {
        // ---- the correspondent's statement names our payment by its own reference, not by our id: unmatched, until a person matches it ----
        Rec paid = firstOf(get("/api/transactions?q=INV-ACK1", "operator1").body(), "items");
        assertNotNull(paid);
        String statementText = Files.readString(WORKSPACE.resolve("tests/messages/mt940-nostro.fin")).replace("STMT-20261005", "STMT-BYHAND")
                .replace("ORVTXN0000000002", "THEIR-REF-7781");
        String message = upload(statementText, "nostro-byhand.fin");
        Rec statement = await("the statement", () -> {
            List<Rec> found = items(quietReply("/api/statements?q=" + message));
            return found.isEmpty() ? null : quietGet("/api/statements/" + found.get(0).str("id"), "operator1");
        });
        List<?> entries = Ops.list(statement.get("entries"));
        Rec first = (Rec) entries.get(0);
        assertEquals("UNMATCHED", first.str("status"), first.toString());
        // the engine suggests what the entry could be: same amount and currency, around the value date, not matched yet
        List<Rec> candidates = items(get("/api/statements/" + statement.str("id") + "/entries/0/candidates", "operator1"));
        assertTrue(candidates.stream().anyMatch(c -> paid.str("id").equals(c.str("id"))), candidates.toString());
        assertEquals(404, get("/api/statements/" + statement.str("id") + "/entries/99/candidates", "operator1").status());
        assertEquals(422, post("/api/statements/" + statement.str("id") + "/entries/0/match", "operator1", Rec.of("transactionId", "ORVTXN-NONE")).status());
        assertEquals(404, post("/api/statements/" + statement.str("id") + "/entries/99/match", "operator1", Rec.of("transactionId", paid.str("id"))).status());
        String before = status(paid.str("id"));
        Rec matched = approve(post("/api/statements/" + statement.str("id") + "/entries/0/match", "operator1",
                Rec.of("transactionId", paid.str("id"), "note", "their reference, our payment: same amount and day", "comment", "checked the advice")));
        assertEquals(paid.str("id"), matched.str("result.transactionId"));
        Rec after = get("/api/statements/" + statement.str("id"), "operator1").body();
        Rec entry = (Rec) Ops.list(after.get("entries")).get(0);
        assertEquals("MATCHED", entry.str("status"));
        assertEquals("operator1 (approved by checker1)".startsWith("operator1") ? paid.str("id") : null, entry.str("transactionId"));
        Rec txn = get("/api/transactions/" + paid.str("id"), "operator1").body();
        assertEquals("MATCHED", txn.str("reconciliation.status"));
        assertEquals(true, txn.at("reconciliation.byHand"));
        assertEquals(statement.str("id"), txn.str("reconciliation.statementId"));
        if ("SENT".equals(before)) {
            assertEquals("ACCEPTED", status(paid.str("id")), "a settling statement settles the payment, matched by hand or not");
        }
        assertEquals(409, post("/api/statements/" + statement.str("id") + "/entries/0/match", "operator1", Rec.of("transactionId", paid.str("id"))).status(), "matched once");

        // ---- the day per channel: what came in, what went out ----
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        Rec report = get("/api/reports/daily?date=" + today, "operator1").body();
        assertEquals(today, report.str("date"));
        List<Rec> rows = items(get("/api/reports/daily?date=" + today, "operator1"));
        Rec corporate = rows.stream().filter(r -> "channels.CorporateIsoInbound".equals(r.str("channel"))).findFirst().orElseThrow();
        assertEquals("inbound", corporate.str("direction"));
        assertTrue(Ops.num(corporate.get("messages")).longValue() > 0 && Ops.num(corporate.get("payments")).longValue() > 0, corporate.toString());
        assertTrue(Ops.num(corporate.get("amount")).signum() > 0);
        Rec swift = rows.stream().filter(r -> "channels.SwiftMtOutbound".equals(r.str("channel"))).findFirst().orElseThrow();
        assertEquals("outbound", swift.str("direction"));
        assertTrue(Ops.num(swift.get("files")).longValue() > 0 && Ops.num(swift.get("payments")).longValue() > 0, swift.toString());
        assertTrue(swift.rec("paymentsByStatus").size() > 0);
        assertEquals(List.of(), items(get("/api/reports/daily?date=2020-01-01", "operator1")), "a day with nothing has no rows");
        HttpResponse<String> csv = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/reports/daily?date=" + today + "&format=csv"))
                .header("Authorization", "Bearer " + token("operator1")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csv.statusCode());
        assertTrue(csv.body().startsWith("﻿channel,") || csv.body().contains("channels.CorporateIsoInbound"), csv.body().substring(0, Math.min(200, csv.body().length())));
    }

    @Test
    @Order(41)
    void aPaymentHeldLongerThanTheChannelAllowsIsTurnedDownAsExpiredNeverReleased() throws Exception {
        // a payment that has been waiting for a person for eleven days, on a channel that allows ten
        String old = "ORVTXN-HELD-OLD";
        store.insert(DocStore.TXN, Rec.of("id", old, "status", "HELD", "channelIn", "channels.CorporateIsoInbound", "instructionId", "none", "endToEndId", "EXPIRED-1",
                "amount", new java.math.BigDecimal("500.00"), "currency", "ZAR", "debtor", Rec.of("name", "Karoo Mining Supplies", "account", "4051122334"),
                "creditor", Rec.of("name", "Normal Supplier", "account", "62011223344"), "hold", Rec.of("code", "FRAUD", "message", "Unusual beneficiary",
                "since", java.time.Instant.now().minusSeconds(11 * 86400L).toString()), "createdAt", java.time.Instant.now().minusSeconds(11 * 86400L).toString(),
                "updatedAt", java.time.Instant.now().minusSeconds(11 * 86400L).toString()));
        // and one held for two days, which keeps waiting
        String recent = "ORVTXN-HELD-NEW";
        store.insert(DocStore.TXN, Rec.of("id", recent, "status", "HELD", "channelIn", "channels.CorporateIsoInbound", "instructionId", "none", "endToEndId", "FRESH-1",
                "amount", new java.math.BigDecimal("500.00"), "currency", "ZAR", "hold", Rec.of("code", "FRAUD", "message", "Unusual beneficiary",
                "since", java.time.Instant.now().minusSeconds(2 * 86400L).toString()), "createdAt", Platform.now(), "updatedAt", Platform.now()));
        Rec expired = await("the old hold to expire", () -> {
            Rec x = quietGet("/api/transactions/" + old, "operator1");
            return "REJECTED_BY_APPLICATION".equals(x.str("status")) ? x : null;
        });
        assertEquals("FRAUD", expired.str("reasonCode"), "turned down with the code it was held for");
        assertTrue(expired.str("reasonText").contains("expired"), expired.str("reasonText"));
        assertTrue(expired.get("events").toString().contains("HOLD_EXPIRED"), expired.get("events").toString());
        Thread.sleep(2500);
        assertEquals("HELD", status(recent), "two days is within the ten the channel allows");
        store.delete(DocStore.TXN, old);
        store.delete(DocStore.TXN, recent);
    }

    @Test
    @Order(42)
    void aRequestToPayWaitsForTheCustomerAndBecomesAPaymentWhenAccepted() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain013-requests.xml"));
        String message = upload(raw, "requests.xml");
        Rec handled = await("the requests to be taken in", () -> {
            Rec m = quietGet("/api/messages/" + message, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
        assertEquals("PROCESSED", handled.str("status"), handled.toString());
        assertEquals("channels.RequestToPayInbound", handled.str("channel"));
        assertEquals(2, Ops.num(handled.get("requestCount")).intValue());
        List<Rec> pending = items(get("/api/requests-to-pay?status=PENDING", "operator1"));
        Rec electricity = pending.stream().filter(r -> "ELECTRICITY-OCT".equals(r.str("endToEndId"))).findFirst().orElseThrow();
        Rec fee = pending.stream().filter(r -> "LATE-FEE-SEP".equals(r.str("endToEndId"))).findFirst().orElseThrow();
        assertEquals("Karoo Mining Supplies", electricity.str("debtor.name"));
        assertEquals("2026-10-20", electricity.str("expiryDate"));

        // refused: the creditor's bank is told RJCT with the reason
        assertEquals(403, post("/api/requests-to-pay/" + fee.str("id") + "/refuse", "viewer1", Rec.of("reasonText", "x")).status());
        Rec refused = approve(post("/api/requests-to-pay/" + fee.str("id") + "/refuse", "operator1", Rec.of("reasonCode", "CUST", "reasonText", "The fee is disputed", "comment", "the customer called")));
        assertEquals("REFUSED", refused.str("result.status"));
        Rec rjct = get("/api/outbound/" + refused.str("result.outboundId"), "operator1").body();
        assertEquals("requestToPayAnswer", rjct.str("kind"));
        assertTrue(rjct.str("payload").contains("pain.014.001.07") && rjct.str("payload").contains("<TxSts>RJCT</TxSts>") && rjct.str("payload").contains("<Cd>CUST</Cd>")
                && rjct.str("payload").contains("<OrgnlEndToEndId>LATE-FEE-SEP</OrgnlEndToEndId>") && rjct.str("payload").contains("<OrgnlMsgId>RTP-2026-10-06-001</OrgnlMsgId>"), rjct.str("payload"));
        assertEquals(409, post("/api/requests-to-pay/" + fee.str("id") + "/accept", "operator1", null).status(), "answered once");

        // accepted: a payment is created from the request and processed; the creditor's bank is told ACCP
        Rec accepted = approve(post("/api/requests-to-pay/" + electricity.str("id") + "/accept", "operator1", Rec.of("comment", "the customer agreed")));
        assertEquals("ACCEPTED", accepted.str("result.status"));
        String txnId = accepted.str("result.transactionId");
        assertNotNull(txnId);
        Rec payment = await("the payment made from the request to be processed", () -> {
            Rec x = quietGet("/api/transactions/" + txnId, "operator1");
            return List.of("SENT", "ACCEPTED", "ROUTED").contains(x.str("status")) || String.valueOf(x.str("status")).startsWith("REJECTED") || "HELD".equals(x.str("status")) ? x : null;
        });
        assertTrue(!String.valueOf(payment.str("status")).startsWith("REJECTED"), payment.toString());
        assertEquals(electricity.str("id"), payment.str("requestToPayId"));
        assertEquals("ELECTRICITY-OCT", payment.str("endToEndId"));
        assertEquals(0, new java.math.BigDecimal("1840.00").compareTo(Ops.num(payment.get("amount"))));
        assertTrue(get("/api/outbound/" + accepted.str("result.outboundId"), "operator1").body().str("payload").contains("<TxSts>ACCP</TxSts>"));
        Rec done = get("/api/requests-to-pay/" + electricity.str("id"), "operator1").body();
        assertEquals("ACCEPTED", done.str("status"));
        assertEquals(txnId, done.str("transactionId"));

        // a request past its expiry date is refused by itself, and the creditor's bank told
        String late = upload(raw.replace("RTP-2026-10-06-001", "RTP-LATE-1").replace("<XpryDt><Dt>2026-10-20</Dt></XpryDt>", "<XpryDt><Dt>2026-10-01</Dt></XpryDt>")
                .replace("ELECTRICITY-OCT", "ELECTRICITY-LATE").replace("LATE-FEE-SEP", "LATE-FEE-LATE"), "requests-late.xml");
        await("the late requests to be taken in", () -> "PROCESSED".equals(quietGet("/api/messages/" + late, "operator1").str("status")));
        List<Rec> expired = await("the late requests to expire", () -> {
            List<Rec> found = new ArrayList<>(items(quietReply("/api/requests-to-pay?status=REFUSED&q=LATE")));
            found.removeIf(r -> !String.valueOf(r.str("endToEndId")).endsWith("-LATE"));
            return found.size() >= 2 ? found : null;
        });
        assertTrue(expired.stream().allMatch(r -> "AB07".equals(Ops.str(r.at("answer.reasonCode")))), expired.toString());
    }

    private static Rec mandateMessage(String fileName, String... replacements) throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/" + fileName));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        String id = upload(raw, fileName);
        return await("the mandate message " + fileName, () -> {
            Rec m = quietGet("/api/messages/" + id, "operator1");
            return "PROCESSED".equals(m.str("status")) || "REJECTED".equals(m.str("status")) ? m : null;
        });
    }

    @Test
    @Order(43)
    void mandateMessagesMaintainTheMandateRegisterAndAreAnsweredWithAcceptanceReports() throws Exception {
        // a new mandate from the creditor's side: registered, and accepted
        Rec created = mandateMessage("pain009-mandate.xml");
        assertEquals("PROCESSED", created.str("status"), created.toString());
        assertEquals("rails.sepa.MandateInbound", created.str("channel"));
        assertEquals(1, Ops.num(created.get("acceptedCount")).intValue(), created.toString());
        Rec mandate = get("/api/x/mandates/MND-MSG-1001", "operator1").body();
        assertEquals("ACTIVE", mandate.str("status"));
        assertEquals("DE98ZZZ09999999999", mandate.str("creditorId"));
        assertEquals("DE08500105175400000042", mandate.str("debtorAccount"));
        assertEquals("MESSAGE", mandate.str("source"));
        Rec yes = get("/api/outbound/" + ((Rec) Ops.list(created.get("results")).get(0)).str("outboundId"), "operator1").body();
        assertEquals("mandateAcceptance", yes.str("kind"));
        assertTrue(yes.str("payload").contains("pain.012.001.07") && yes.str("payload").contains("<Accptd>true</Accptd>") && yes.str("payload").contains("<OrgnlMndtId>MND-MSG-1001</OrgnlMndtId>")
                && yes.str("payload").contains("<MsgId>MNDT-2026-10-06-001</MsgId>"), yes.str("payload"));
        // the same mandate again is refused: it exists
        Rec again = mandateMessage("pain009-mandate.xml", "MNDT-2026-10-06-001", "MNDT-AGAIN-1");
        assertEquals(1, Ops.num(again.get("refusedCount")).intValue(), again.toString());
        assertTrue(get("/api/outbound/" + ((Rec) Ops.list(again.get("results")).get(0)).str("outboundId"), "operator1").body().str("payload").contains("<Accptd>false</Accptd>"));

        // amended: the debtor moved the mandate to another account
        Rec amended = mandateMessage("pain010-amendment.xml");
        assertEquals(1, Ops.num(amended.get("acceptedCount")).intValue(), amended.toString());
        Rec moved = get("/api/x/mandates/MND-MSG-1001", "operator1").body();
        assertEquals("AMENDED", moved.str("status"));
        assertEquals("DE49500105175550000007", moved.str("debtorAccount"));
        assertEquals("MD16", moved.str("reason"));

        // a mandate for a creditor the debtor has blocked is refused with the reason
        String account = "DE08500105175400000042";
        assertEquals(200, send("PUT", "/api/x/debit-blocks/" + account + "/DE11ZZZ00000055555", "checker1", "application/json", "{\"note\": \"the customer blocked this creditor\"}").status());
        Rec blocked = mandateMessage("pain009-mandate.xml", "MNDT-2026-10-06-001", "MNDT-BLOCKED-1", "MND-MSG-1001", "MND-MSG-BLOCKED", "DE98ZZZ09999999999", "DE11ZZZ00000055555");
        assertEquals(1, Ops.num(blocked.get("refusedCount")).intValue(), blocked.toString());
        String no = get("/api/outbound/" + ((Rec) Ops.list(blocked.get("results")).get(0)).str("outboundId"), "operator1").body().str("payload");
        assertTrue(no.contains("<Accptd>false</Accptd>") && no.contains("blocked"), no);
        assertEquals(404, get("/api/x/mandates/MND-MSG-BLOCKED", "operator1").status(), "nothing was registered");
        send("DELETE", "/api/x/debit-blocks/" + account + "/DE11ZZZ00000055555", "checker1", "application/json", null);

        // cancelled: the row stays, cancelled; an unknown mandate cannot be cancelled
        Rec cancelled = mandateMessage("pain011-cancellation.xml");
        assertEquals(1, Ops.num(cancelled.get("acceptedCount")).intValue(), cancelled.toString());
        assertEquals("CANCELLED", get("/api/x/mandates/MND-MSG-1001", "operator1").body().str("status"));
        Rec unknown = mandateMessage("pain011-cancellation.xml", "MNDT-2026-10-06-003", "MNDT-UNKNOWN-1", "MND-MSG-1001", "MND-NEVER-SEEN");
        assertEquals(1, Ops.num(unknown.get("refusedCount")).intValue(), unknown.toString());
    }

    @Test
    @Order(44)
    void aPaymentIsRepairedWithChangesNotedAndAWarehousedOneReleasedBeforeItsTime() throws Exception {
        // ---- repair: the currency nobody quotes a rate for is corrected, the payment goes on ----
        String broken = onePayment("REPAIR-FIX-1", "Tokyo Parts KK", "JPY", "CHASUS33", "4051122334");
        await("the payment to be parked for repair", () -> "REPAIR".equals(status(broken)));
        assertEquals(422, post("/api/transactions/" + broken + "/resubmit", "operator1", Rec.of("changes", Rec.of("id", "ORVTXN-OTHER"))).status(), "ids are not repairable");
        assertEquals(422, post("/api/transactions/" + broken + "/resubmit", "operator1", Rec.of("changes", Rec.of("amount", -5))).status());
        Rec repaired = approve(post("/api/transactions/" + broken + "/resubmit", "operator1",
                Rec.of("changes", Rec.of("currency", "ZAR", "creditor.agentBic", "FIRNZAJJ", "remittance", "Corrected by the bank"), "comment", "the customer confirmed rand")));
        assertTrue(repaired.str("summary").startsWith("Repair and resubmit"), repaired.str("summary"));
        Rec fixed = await("the repaired payment to go on", () -> {
            Rec x = quietGet("/api/transactions/" + broken, "operator1");
            return !"REPAIR".equals(x.str("status")) && !"CREATED".equals(x.str("status")) && !"PROCESSING".equals(x.str("status")) ? x : null;
        });
        assertEquals("ZAR", fixed.str("currency"));
        assertEquals("FIRNZAJJ", fixed.str("creditor.agentBic"));
        assertTrue(!String.valueOf(fixed.str("status")).startsWith("REJECTED") || !"CONNECTOR_REFUSED".equals(fixed.str("reasonCode")), fixed.toString());
        Rec repair = (Rec) Ops.list(fixed.get("repairs")).get(0);
        assertEquals("JPY", ((Rec) repair.rec("changes").get("currency")).str("from"));
        assertEquals("ZAR", ((Rec) repair.rec("changes").get("currency")).str("to"));
        assertTrue(fixed.get("events").toString().contains("REPAIRED"));

        // ---- a note: kept with the payment, with who and when ----
        assertEquals(422, post("/api/transactions/" + broken + "/notes", "operator1", Rec.of("text", "  ")).status());
        Rec noted = post("/api/transactions/" + broken + "/notes", "operator1", Rec.of("text", "Customer called: the invoice is in rand, not yen.")).body();
        assertEquals(1, Ops.list(noted.get("notes")).size());
        Rec again = get("/api/transactions/" + broken, "operator1").body();
        assertEquals("operator1", ((Rec) Ops.list(again.get("notes")).get(0)).str("by"));
        assertTrue(again.get("events").toString().contains("NOTE"));

        // ---- a warehoused payment released before its time, with a second person ----
        Rec waiting = get("/api/transactions/" + broken, "operator1").body().copy();
        for (String key : List.of("outboundId", "cancellation", "route", "screening", "acknowledgementId", "reasonCode", "reasonText", "reportedStatus", "events", "posting", "checks", "fx", "notify", "delivery")) {
            waiting.remove(key);
        }
        String parked = "ORVTXN-WAREHOUSED-1";
        waiting.put("id", parked);
        waiting.put("instructionId", "none");
        waiting.put("endToEndId", "WAREHOUSED-1");
        waiting.put("status", "WAREHOUSED");
        waiting.put("warehouse", Rec.of("until", java.time.Instant.now().plusSeconds(3 * 86400).toString(), "code", "DATE", "reason", "Requested for a later day"));
        store.insert(DocStore.TXN, waiting);
        assertEquals(409, post("/api/transactions/" + broken + "/release-now", "operator1", null).status(), "only a warehoused payment");
        approve(post("/api/transactions/" + parked + "/release-now", "operator1", Rec.of("comment", "the customer asked for it today")));
        Rec released = await("the released payment to be processed", () -> {
            Rec x = quietGet("/api/transactions/" + parked, "operator1");
            return !List.of("WAREHOUSED", "CREATED", "PROCESSING").contains(x.str("status")) ? x : null;
        });
        assertTrue(released.get("events").toString().contains("RELEASED"), released.get("events").toString());
        // the processing that follows writes the payment anew, so who released it lives in the event, not on the record
    }

    @Test
    @Order(45)
    void anApproverMayApproveOnlyUpToTheRolesLimit() throws Exception {
        // a role that approves payment actions up to 500 rand, and a user who has it
        assertEquals(400, post("/api/roles", "maker1", Rec.of("id", "JUNIOR_APPROVER", "permissions", List.of("payments.view", "payments.approve"),
                "approvalLimits", Rec.of("ZAR", -1))).status());
        approve(post("/api/roles", "maker1", Rec.of("id", "JUNIOR_APPROVER", "description", "Approves small payment actions", "permissions", List.of("payments.view", "payments.approve"),
                "approvalLimits", Rec.of("ZAR", 500, "*", 100))));
        String password = "junior-Approver-pass-55";
        approve(post("/api/users", "maker1", Rec.of("username", "junior1", "displayName", "Junior Approver", "roles", List.of("JUNIOR_APPROVER"), "status", "ACTIVE", "password", password)));
        signInNew("junior1", password);
        Rec me = get("/api/me", "junior1").body();
        assertEquals(500, Ops.num(me.at("approvalLimits.ZAR")).intValue());

        // a request on a 900 rand payment: above the limit, so the junior approver is refused and a senior one decides
        String big = onePayment("LIMIT-BIG-1", "Tokyo Parts KK", "JPY", "CHASUS33", "4051122334");
        await("the payment to be parked", () -> "REPAIR".equals(status(big)));
        Reply request = post("/api/transactions/" + big + "/resubmit", "operator1", Rec.of("changes", Rec.of("currency", "ZAR"), "comment", "rand after all"));
        Reply refused = post("/api/approvals/" + request.body().str("id") + "/approve", "junior1", Rec.of("comment", "fine by me"));
        assertEquals(403, refused.status(), refused.body().toString());
        assertTrue(refused.body().str("error").contains("above what your role may approve"), refused.body().toString());
        assertEquals("PENDING", get("/api/approvals/" + request.body().str("id"), "operator1").body().str("status"), "nothing was decided");
        assertEquals("APPROVED", post("/api/approvals/" + request.body().str("id") + "/approve", "checker1", Rec.of("comment", "checked")).body().str("status"));
        assertTrue(items(get("/api/security/events?type=DENIED", "maker1")).stream().anyMatch(e -> "junior1".equals(e.str("username")) && String.valueOf(e.str("detail")).contains("approval limit")));

        // a request on a payment within the limit: the junior approver decides it
        Rec small = get("/api/transactions/" + big, "operator1").body().copy();
        for (String key : List.of("outboundId", "cancellation", "route", "screening", "acknowledgementId", "reasonCode", "reasonText", "reportedStatus", "events", "posting", "checks", "fx", "notify", "repairs", "notes")) {
            small.remove(key);
        }
        small.put("id", "ORVTXN-SMALL-WH");
        small.put("instructionId", "none");
        small.put("endToEndId", "SMALL-WH-1");
        small.put("amount", new java.math.BigDecimal("120.00"));
        small.put("currency", "ZAR");
        small.put("status", "WAREHOUSED");
        small.put("warehouse", Rec.of("until", java.time.Instant.now().plusSeconds(86400).toString(), "code", "DATE"));
        store.insert(DocStore.TXN, small);
        Reply release = post("/api/transactions/ORVTXN-SMALL-WH/release-now", "operator1", Rec.of("comment", "today please"));
        Reply decided = post("/api/approvals/" + release.body().str("id") + "/approve", "junior1", Rec.of("comment", "within my limit"));
        assertEquals("APPROVED", decided.body().str("status"), decided.body().toString());
        // approvals that are not about a payment are not limited by amount
        assertTrue(post("/api/approvals/" + post("/api/users", "maker1", Rec.of("username", "junior1", "displayName", "Junior Approver (renamed)", "roles", List.of("JUNIOR_APPROVER"), "status", "ACTIVE")).body().str("id") + "/approve",
                "checker1", Rec.of("comment", "ok")).status() == 200);
    }

    @Test
    @Order(46)
    void aReferenceTableIsDownloadedAndUploadedAsASpreadsheetFileWithApproval() throws Exception {
        // the rows go out as a file, with every column
        HttpResponse<String> file = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/studio/reference-tables/reference.BicDirectory/rows.csv"))
                .header("Authorization", "Bearer " + token("maker1")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, file.statusCode(), file.body());
        assertTrue(file.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        String[] lines = file.body().replace("﻿", "").split("\r\n");
        assertEquals("bic,name,country", lines[0]);
        assertTrue(List.of(lines).contains("FIRNZAJJ,FirstRand Bank,ZA"), file.body());
        assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/studio/reference-tables/reference.Nowhere/rows.csv"))
                .header("Authorization", "Bearer " + token("maker1")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(409, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/studio/reference-tables/api.rules.AccountLimitRequest/rows.csv"))
                .header("Authorization", "Bearer " + token("maker1")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not a reference table");

        // the file comes back with a bank added and a name changed: a change request a second person approves
        String path = "/api/studio/reference-tables/reference.BicDirectory/imports";
        assertEquals(403, post(path, "operator1", Rec.of("csv", "bic,name,country\r\nTESTZAJJ,Test Bank,ZA\r\n")).status());
        assertEquals(422, post(path, "maker1", Rec.of("csv", "name,country\r\nTest Bank,ZA\r\n")).status(), "the key column is needed");
        assertEquals(422, post(path, "maker1", Rec.of("csv", "bic,name,country\r\nTESTZAJJ,Test Bank\r\n")).status(), "a short line");
        assertEquals(422, post(path, "maker1", Rec.of("csv", "bic,name,country\r\n")).status(), "no rows");
        assertEquals(422, post(path, "maker1", Rec.of("csv", "bic,name,country\r\n,Test Bank,ZA\r\n")).status(), "no key value");
        assertEquals(422, post(path, "maker1", Rec.of("csv", "bic,name,country\r\nTESTZAJJ,Test Bank,ZA\r\n", "mode", "append")).status());
        Reply request = post(path, "maker1", Rec.of("csv", "﻿bic,name,country\r\nTESTZAJJ,\"Test Bank, Limited\",ZA\r\nFIRNZAJJ,\"FirstRand Bank \"\"FNB\"\"\",ZA\r\n\r\n", "comment", "two banks from the directory file"));
        assertEquals(200, request.status(), request.body().toString());
        assertEquals("MODEL_CHANGE", request.body().str("type"));
        assertTrue(request.body().str("summary").contains("1 added, 1 changed, 0 removed"), request.body().str("summary"));
        assertEquals("merge", request.body().at("payload.import.mode"));
        approve(request);
        Rec table = get("/api/studio/models/reference.BicDirectory", "maker1").body();
        List<Rec> rows = Ops.list(table.at("definition.rows")).stream().map(r -> (Rec) r).toList();
        assertTrue(rows.stream().anyMatch(r -> "TESTZAJJ".equals(r.str("bic")) && "Test Bank, Limited".equals(r.str("name"))), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "FIRNZAJJ".equals(r.str("bic")) && "FirstRand Bank \"FNB\"".equals(r.str("name"))), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "CHASUS33".equals(r.str("bic"))), "the other rows stay in a merge");
        assertTrue(table.str("text").contains("Financial institutions known to the platform"), "the description stays");

        // the whole table replaced by a file: rows not in the file are removed
        Reply whole = post("/api/studio/reference-tables/reference.Holidays/imports", "maker1", Rec.of("mode", "replace",
                "csv", "key,calendar,date,name\nTARGET2|2026-12-25,TARGET2,2026-12-25,Christmas Day\nZA|2026-12-16,ZA,2026-12-16,Day of Reconciliation\n", "comment", "two days"));
        assertEquals(200, whole.status(), whole.body().toString());
        assertEquals(2, Ops.num(whole.body().at("payload.import.rows")).intValue());
        assertEquals(1, Ops.num(whole.body().at("payload.import.added")).intValue());
        assertEquals(11, Ops.num(whole.body().at("payload.import.removed")).intValue());
        approve(whole);
        assertEquals(2, Ops.list(get("/api/studio/models/reference.Holidays", "maker1").body().at("definition.rows")).size());
        // taking the rows out was logged
        assertTrue(items(get("/api/security/events?type=EXPORT", "maker1")).stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("reference.BicDirectory")));
    }

    @Test
    @Order(47)
    void aPaymentIsInitiatedFromAFormWithATemplateAndEntersProcessingOnceApproved() throws Exception {
        // a template keeps the parties; the shape of every field is checked
        assertEquals(403, post("/api/payments/templates", "viewer1", Rec.of("name", "Tokyo Parts", "fields", Rec.of())).status());
        assertEquals(422, post("/api/payments/templates", "operator1", Rec.of("name", "Tokyo Parts", "fields", Rec.of("creditor.agentBic", "not-a-bic"))).status());
        assertEquals(422, post("/api/payments/templates", "operator1", Rec.of("name", "Tokyo Parts", "fields", Rec.of("status", "ACCEPTED"))).status(), "not a field of a payment");
        Rec template = post("/api/payments/templates", "operator1", Rec.of("name", "Tokyo Parts", "fields", Rec.of("debtor.name", "Karoo Mining Supplies", "debtor.account", "4051122334",
                "creditor.name", "Tokyo Parts KK", "creditor.account", "62011223344", "creditor.agentBic", "FIRNZAJJ", "currency", "ZAR", "chargeBearer", "SHAR"))).body();
        assertEquals("tokyo-parts", template.str("id"));
        assertTrue(items(get("/api/payments/templates", "operator1")).stream().anyMatch(x -> "tokyo-parts".equals(x.str("id"))));

        // a payment from the template: what is missing is refused, what is given overrides the template
        String path = "/api/payments/initiations";
        assertEquals(422, post(path, "operator1", Rec.of("template", "tokyo-parts")).status(), "the amount is needed");
        assertEquals(422, post(path, "operator1", Rec.of("template", "tokyo-parts", "amount", "-1")).status());
        assertEquals(422, post(path, "operator1", Rec.of("template", "tokyo-parts", "amount", "450", "requestedDate", "someday")).status());
        assertEquals(404, post(path, "operator1", Rec.of("template", "nowhere", "amount", "450")).status());
        Reply request = post(path, "operator1", Rec.of("template", "tokyo-parts", "amount", "450.00", "remittance", "Invoice 2026-118", "endToEndId", "FORM-450-1", "comment", "the October invoice"));
        assertEquals(200, request.status(), request.body().toString());
        assertEquals("PENDING", request.body().str("status"));
        assertTrue(request.body().str("summary").startsWith("Initiate a payment of 450.00 ZAR from 4051122334 to Tokyo Parts KK"), request.body().str("summary"));
        assertEquals("INITIATE", request.body().at("payload.action"));
        assertTrue(items(get("/api/transactions?endToEndId=FORM-450-1", "operator1")).isEmpty(), "nothing is processed before approval");
        // within the junior approver's limit (ZAR 500): the junior approver decides, and the payment enters the engine
        // (the junior approver's record was changed at the end of the earlier scenario, which ended every session of theirs)
        signInNew("junior1", "junior-Approver-pass-55");
        Rec decided = post("/api/approvals/" + request.body().str("id") + "/approve", "junior1", Rec.of("comment", "within my limit")).body();
        assertEquals("APPROVED", decided.str("status"), decided.toString());
        String instructionId = decided.rec("result").str("instructionId");
        Rec txn = await("the initiated payment", () -> transactionsOf(instructionId).get("FORM-450-1"));
        assertEquals("450.00", Ops.num(txn.get("amount")).toPlainString());
        assertEquals("Invoice 2026-118", txn.str("remittance"));
        assertEquals("SHAR", txn.str("chargeBearer"));
        Rec instruction = get("/api/messages/" + instructionId, "operator1").body();
        assertEquals("channels.ConsoleInitiation", instruction.str("channel"));
        assertEquals("operator1", instruction.str("initiatingParty"));
        await("the initiated payment to be processed", () -> !List.of("CREATED", "PROCESSING").contains(status(txn.str("id"))));
        assertTrue(!String.valueOf(status(txn.str("id"))).startsWith("REJECTED") || !"CONNECTOR_REFUSED".equals(get("/api/transactions/" + txn.str("id"), "operator1").body().str("reasonCode")), get("/api/transactions/" + txn.str("id"), "operator1").body().toString());

        // above the limit: the junior approver is refused, a senior one approves
        Reply big = post(path, "operator1", Rec.of("template", "tokyo-parts", "amount", "900", "endToEndId", "FORM-900-1"));
        assertEquals(403, post("/api/approvals/" + big.body().str("id") + "/approve", "junior1", Rec.of("comment", "fine")).status());
        approve(big);

        // the Console channel takes nothing from outside: a file that looks like a form payment is refused
        String fake = Json.write(Rec.of("messageType", "payment.initiation", "msgId", "FAKE-1", "endToEndId", "FAKE-1", "amount", 5, "currency", "ZAR",
                "debtor", Rec.of("account", "4051122334"), "creditor", Rec.of("name", "Nobody", "account", "1")));
        assertEquals(403, send("POST", "/api/inbound?channel=channels.ConsoleInitiation", "operator1", "application/json", fake).status());
        Reply loose = send("POST", "/api/inbound", "operator1", "application/json", fake);
        assertEquals(200, loose.status(), loose.body().toString());
        assertEquals("NO_CHANNEL", ((Rec) Ops.list(loose.body().get("messages")).get(0)).str("reasonCode"));
        // a template is removed
        assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/payments/templates/tokyo-parts")).DELETE()
                .header("Authorization", "Bearer " + token("operator1")).header("X-Orvanta-Console", "1").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(items(get("/api/payments/templates", "operator1")).stream().noneMatch(x -> "tokyo-parts".equals(x.str("id"))));
    }

    @Test
    @Order(48)
    void aSystemCallsTheApiWithAKeyThatPeopleIssueAndMayNeverApprove() throws Exception {
        assertEquals(400, post("/api/keys", "maker1", Rec.of("id", "Bad Name", "roles", List.of("OPERATOR"))).status());
        assertEquals(400, post("/api/keys", "maker1", Rec.of("id", "approver-key", "roles", List.of("APPROVER"))).status(), "a key may not approve");
        assertEquals(400, post("/api/keys", "maker1", Rec.of("id", "no-roles")).status());
        Reply request = post("/api/keys", "maker1", Rec.of("id", "erp-system", "description", "The ERP posting salaries", "roles", List.of("OPERATOR"), "comment", "for the ERP"));
        assertEquals(200, request.status(), request.body().toString());
        String key = request.body().str("key");
        assertTrue(key != null && key.startsWith("orv_erp-system_"), String.valueOf(key));
        assertEquals(null, request.body().at("payload.keyHash"), "the hash stays on the server");
        // not before approval
        assertEquals(401, send("GET", "/api/transactions?limit=1", null, "application/json", null, "X-Api-Key", key).status());
        approve(request);
        Reply listed = send("GET", "/api/transactions?limit=1", null, "application/json", null, "X-Api-Key", key);
        assertEquals(200, listed.status(), listed.body().toString());
        assertEquals(401, send("GET", "/api/transactions?limit=1", null, "application/json", null, "X-Api-Key", key + "x").status());
        assertTrue(items(get("/api/security/events?type=DENIED", "maker1")).stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("API key refused")));
        // what the roles do not allow, deciding, and making keys are refused to a key
        assertEquals(403, send("GET", "/api/users", null, "application/json", null, "X-Api-Key", key).status());
        Reply someRequest = post("/api/users", "maker1", Rec.of("username", "viewer1", "displayName", "viewer1", "roles", List.of(), "status", "ACTIVE"));
        assertEquals(403, send("POST", "/api/approvals/" + someRequest.body().str("id") + "/approve", null, "application/json", "{}", "X-Api-Key", key).status());
        assertEquals(403, send("POST", "/api/keys", null, "application/json", Json.write(Rec.of("id", "another", "roles", List.of("OPERATOR"))), "X-Api-Key", key).status());
        Rec shown = items(get("/api/keys", "maker1")).stream().filter(k -> "erp-system".equals(k.str("id"))).findFirst().orElseThrow();
        assertEquals(null, shown.get("keyHash"));
        assertNotNull(shown.str("lastUsedAt"));
        assertEquals("The ERP posting salaries", shown.str("description"));
        // the approval record shows no hash either
        assertEquals(null, get("/api/approvals/" + request.body().str("id"), "maker1").body().at("payload.keyHash"));
        // disabled with a second person: the key stops working at once
        approve(post("/api/keys", "maker1", Rec.of("id", "erp-system", "description", "The ERP posting salaries", "roles", List.of("OPERATOR"), "status", "DISABLED")));
        assertEquals(401, send("GET", "/api/transactions?limit=1", null, "application/json", null, "X-Api-Key", key).status());
        // rotated: the old secret is void, the new one works
        Reply rotated = post("/api/keys", "maker1", Rec.of("id", "erp-system", "description", "The ERP posting salaries", "roles", List.of("OPERATOR"), "status", "ACTIVE", "rotate", true));
        String newKey = rotated.body().str("key");
        assertTrue(newKey != null && !newKey.equals(key));
        approve(rotated);
        assertEquals(401, send("GET", "/api/transactions?limit=1", null, "application/json", null, "X-Api-Key", key).status());
        assertEquals(200, send("GET", "/api/transactions?limit=1", null, "application/json", null, "X-Api-Key", newKey).status());
    }

    private static HttpResponse<String> soap(String user, String body) throws Exception {
        String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>" + body + "</soapenv:Body></soapenv:Envelope>";
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/soap")).header("Content-Type", "text/xml").header("Authorization", "Bearer " + token(user))
                .POST(HttpRequest.BodyPublishers.ofString(envelope)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(50)
    void theModelDefinedApisAreAlsoSoapOperations() throws Exception {
        String account = "7700998877";
        // set through SOAP, read through SOAP and through REST: the same flow behind both
        HttpResponse<String> set = soap("checker1", "<SetAccountLimit xmlns=\"urn:orvanta:api\"><account>" + account + "</account><perTransaction>750</perTransaction></SetAccountLimit>");
        assertEquals(200, set.statusCode(), set.body());
        assertTrue(set.body().contains("<SetAccountLimitResponse xmlns=\"urn:orvanta:api\">") && set.body().contains("<perTransaction>750</perTransaction>"), set.body());
        HttpResponse<String> got = soap("operator1", "<GetAccountLimit><account>" + account + "</account></GetAccountLimit>");
        assertEquals(200, got.statusCode(), got.body());
        assertTrue(got.body().contains("<account>" + account + "</account>") && got.body().contains("<perTransaction>750</perTransaction>"), got.body());
        assertEquals(750, Ops.num(get("/api/x/limits/" + account, "operator1").body().get("perTransaction")).intValue());
        // what the model refuses is a Client fault with the detail; a missing path element, an unknown operation and no envelope are faults too
        HttpResponse<String> refused = soap("checker1", "<SetAccountLimit><account>" + account + "</account><perTransaction>0</perTransaction></SetAccountLimit>");
        assertEquals(422, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("<faultcode>soap:Client</faultcode>") && refused.body().contains("AM01"), refused.body());
        assertEquals(400, soap("operator1", "<GetAccountLimit/>").statusCode());
        assertEquals(400, soap("operator1", "<NoSuchOperation/>").statusCode());
        HttpResponse<String> plain = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/soap")).header("Content-Type", "text/xml")
                .header("Authorization", "Bearer " + token("operator1")).POST(HttpRequest.BodyPublishers.ofString("<nothing/>")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, plain.statusCode());
        // the permission of the REST form applies: an operator may read limits, not set them
        assertEquals(403, soap("operator1", "<SetAccountLimit><account>" + account + "</account><perTransaction>5</perTransaction></SetAccountLimit>").statusCode());
        // the service description lists the operations
        HttpResponse<String> wsdl = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/soap?wsdl")).header("Authorization", "Bearer " + token("operator1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, wsdl.statusCode());
        assertTrue(wsdl.body().contains("<wsdl:operation name=\"GetAccountLimit\">") && wsdl.body().contains("<wsdl:operation name=\"SetAccountLimit\">"), wsdl.body());
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/soap?wsdl")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, send("DELETE", "/api/x/limits/" + account, "checker1", "application/json", null).status());
    }

    @Test
    @Order(49)
    void theModelDefinedApisAreDescribedForToolsInOpenApi() throws Exception {
        assertEquals(401, send("GET", "/api/openapi.json", null, "application/json", null).status(), "the description is for callers who may call");
        Rec doc = get("/api/openapi.json", "operator1").body();
        assertEquals("3.0.3", doc.str("openapi"));
        assertEquals("/api/x", ((Rec) Ops.list(doc.get("servers")).get(0)).str("url"));
        Rec limits = (Rec) doc.rec("paths").get("/limits/{account}");
        assertNotNull(limits, doc.rec("paths").keySet().toString());
        assertTrue(limits.containsKey("get") && limits.containsKey("put") && limits.containsKey("delete"), limits.keySet().toString());
        Rec put = limits.rec("put");
        assertEquals("api.AccountLimitPut", put.str("operationId"));
        assertEquals("payments.approve", put.str("x-orvanta-permission"));
        assertEquals("account", ((Rec) Ops.list(put.get("parameters")).get(0)).str("name"));
        assertEquals(50000, Ops.num(put.at("requestBody.content.application/json.schema.example.perTransaction")).intValue());
        assertTrue(put.rec("responses").containsKey("200") && put.rec("responses").containsKey("403"));
        assertEquals(null, limits.rec("get").get("requestBody"), "a GET has no body");
        assertEquals("X-Api-Key", doc.at("components.securitySchemes.apiKey.name"));
        assertEquals(String.valueOf(get("/api/studio/models", "maker1").body().get("version")), doc.at("info.version"));
    }

    @Test
    @Order(50)
    void aModelDefinedApiThatAsksForApprovalRunsOnlyWhenASecondPersonApproves() throws Exception {
        // an Api model with approval: true, deployed like any model
        String model = "kind: Api\nname: api.LimitApproved\ndescription: Sets a limit, with a second person.\nmethod: PUT\npath: /limits-approved/{account}\n"
                + "permission: payments.submit\napproval: true\ntarget: api.flows.AccountLimitWrite\n";
        assertEquals(422, post("/api/studio/changes", "maker1", Rec.of("text", model.replace("approval: true", "approval: maybe"))).status());
        approve(post("/api/studio/changes", "maker1", Rec.of("text", model, "comment", "limits with approval")));
        Rec doc = get("/api/openapi.json", "operator1").body();
        assertEquals(true, ((Rec) doc.rec("paths").get("/limits-approved/{account}")).rec("put").get("x-orvanta-approval"));

        // the call is recorded, not run
        Reply asked = send("PUT", "/api/x/limits-approved/4051122334?comment=for+the+scenario", "operator1", "application/json", Json.write(Rec.of("perTransaction", 777)));
        assertEquals(202, asked.status(), asked.body().toString());
        assertEquals("PENDING", asked.body().str("status"));
        assertEquals("API_CALL", asked.body().str("type"));
        assertTrue(asked.body().str("summary").startsWith("PUT /api/x/limits-approved/4051122334 through api.LimitApproved"), asked.body().str("summary"));
        Rec before = get("/api/x/limits/4051122334", "operator1").body();
        assertFalse(before.get("perTransaction") != null && "777".equals(Ops.num(before.get("perTransaction")).toPlainString()), before.toString());
        // the maker cannot approve their own call; a second person does, and the change is made as the maker
        assertEquals(403, post("/api/approvals/" + asked.body().str("id") + "/approve", "operator1", Rec.of("comment", "me")).status());
        Rec decided = post("/api/approvals/" + asked.body().str("id") + "/approve", "checker1", Rec.of("comment", "checked")).body();
        assertEquals("APPROVED", decided.str("status"), decided.toString());
        assertEquals(200, Ops.num(decided.at("result.http")).intValue());
        assertEquals("777", Ops.num(get("/api/x/limits/4051122334", "operator1").body().get("perTransaction")).toPlainString());
        // reading through an API with approval needs no second person
        assertEquals(200, get("/api/x/limits/4051122334", "operator1").status());
    }

    @Test
    @Order(51)
    void aPartnerPushesFilesWithItsOwnKeyWhichIsRotatedWithoutARedeployment() throws Exception {
        assertEquals(400, post("/api/keys", "maker1", Rec.of("id", "partner-push", "roles", List.of("OPERATOR"), "channels", List.of("channels.Nowhere"))).status());
        Reply request = post("/api/keys", "maker1", Rec.of("id", "partner-push", "description", "The corporate's file sender", "roles", List.of("OPERATOR"),
                "channels", List.of("channels.CorporateIsoInbound"), "comment", "for the partner"));
        String key = request.body().str("key");
        approve(request);
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-KEY-1");
        // the channel's own static key still works; the system's key works too, for its channel only
        Reply pushed = send("POST", "/in/channels.CorporateIsoInbound?fileName=by-key.xml", null, "application/xml", raw, "X-Api-Key", key);
        assertEquals(202, pushed.status(), pushed.body().toString());
        Rec message = (Rec) Ops.list(pushed.body().get("messages")).get(0);
        assertEquals("key:partner-push", store.get(DocStore.MESSAGE, message.str("id")).str("receivedBy"));
        assertEquals(403, send("POST", "/in/channels.CorporateMtInbound", null, "text/plain", "{1:F01}", "X-Api-Key", key).status(), "another channel");
        assertEquals(403, send("POST", "/in/channels.CorporateIsoInbound", null, "application/xml", raw, "X-Api-Key", key + "x").status());
        // a key without the permission to submit is refused
        Reply viewer = post("/api/keys", "maker1", Rec.of("id", "partner-viewer", "roles", List.of("VIEWER")));
        if (viewer.status() == 200) {
            approve(viewer);
            assertEquals(403, send("POST", "/in/channels.CorporateIsoInbound", null, "application/xml", raw, "X-Api-Key", viewer.body().str("key")).status());
        }
        // rotated in the Console: the old secret stops, the new one goes on, nothing was redeployed
        Reply rotated = post("/api/keys", "maker1", Rec.of("id", "partner-push", "description", "The corporate's file sender", "roles", List.of("OPERATOR"),
                "channels", List.of("channels.CorporateIsoInbound"), "status", "ACTIVE", "rotate", true));
        approve(rotated);
        assertEquals(403, send("POST", "/in/channels.CorporateIsoInbound", null, "application/xml", raw, "X-Api-Key", key).status());
        assertEquals(202, send("POST", "/in/channels.CorporateIsoInbound?fileName=by-new-key.xml", null, "application/xml", raw.replace("SALARY-KEY-1", "SALARY-KEY-2"), "X-Api-Key", rotated.body().str("key")).status());
        assertTrue(items(get("/api/security/events?type=DENIED", "maker1")).stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("/in/channels.CorporateIsoInbound")));
    }

    @Test
    @Order(52)
    void aPartnerSubmitsAnInstructionAsASoapRequestAndGetsAReceiptEnvelope() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-SOAP-1");
        String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Header/><soapenv:Body>" + raw + "</soapenv:Body></soapenv:Envelope>";
        // the channel's key, as on /in/<channel>
        HttpResponse<String> refused = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/in/soap/channels.CorporateIsoInbound"))
                .header("Content-Type", "text/xml").header("X-Api-Key", "not-the-key-at-all").POST(HttpRequest.BodyPublishers.ofString(envelope)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("<faultcode>soap:Client</faultcode>"), refused.body());
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/in/soap/channels.CorporateIsoInbound?fileName=soap.xml"))
                .header("Content-Type", "text/xml").header("X-Api-Key", CHANNEL_KEY).POST(HttpRequest.BodyPublishers.ofString(envelope)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, answer.statusCode(), answer.body());
        assertTrue(answer.headers().firstValue("Content-Type").orElse("").startsWith("text/xml"));
        Rec receipt = io.orvanta.core.format.IsoXml.parse(answer.body());
        Rec item = (Rec) Ops.list(receipt.at("Envelope.Body.Receipts.receipt") instanceof List<?> l ? l : List.of(receipt.at("Envelope.Body.Receipts.receipt"))).get(0);
        assertTrue(String.valueOf(item.str("messageId")).startsWith("ORVINS"), item.toString());
        assertEquals("RECEIVED", item.str("status"));
        assertEquals("soap:channels.CorporateIsoInbound", store.get(DocStore.MESSAGE, item.str("messageId")).str("receivedBy"));
        await("the soap-submitted payments", () -> transactionsOf(item.str("messageId")).isEmpty() ? null : Boolean.TRUE);
        // not an envelope: a fault that blames the request
        HttpResponse<String> fault = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/in/soap/channels.CorporateIsoInbound"))
                .header("Content-Type", "text/xml").header("X-Api-Key", CHANNEL_KEY).POST(HttpRequest.BodyPublishers.ofString("<nothing/>")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, fault.statusCode());
        assertTrue(fault.body().contains("<faultcode>soap:Client</faultcode>"), fault.body());
        // a message the channel does not take is a receipt saying so, with 422
        HttpResponse<String> wrong = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/in/soap/channels.CorporateIsoInbound"))
                .header("Content-Type", "text/xml").header("X-Api-Key", CHANNEL_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(envelope.replace(raw, "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:acmt.023.001.03\"><IdVrfctnReq/></Document>"))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(422, wrong.statusCode(), wrong.body());
        assertTrue(wrong.body().contains("<reasonCode>NO_CHANNEL</reasonCode>"), wrong.body());
    }

    @Test
    @Order(53)
    void aPurposeCodeTheClearingDoesNotTakeIsRepairedAndAllSendingCanBeStoppedAtOnce() throws Exception {
        // ---- a purpose code the ZA clearing does not take: parked for repair, corrected, and on it goes ----
        String msgId = "PURP-1";
        String raw = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>" + msgId
                + "</MsgId><CreDtTm>2026-10-07T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>" + msgId + "-1</PmtInfId>"
                + "<ReqdExctnDt><Dt>2026-10-07</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>4051122334</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>" + msgId + "</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"ZAR\">120.00</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>FIRNZAJJ</BICFI></FinInstnId></CdtrAgt>"
                + "<Cdtr><Nm>Thandiwe Mokoena</Nm></Cdtr><CdtrAcct><Id><Othr><Id>62011223344</Id></Othr></Id></CdtrAcct><Purp><Cd>XXXX</Cd></Purp>"
                + "</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
        String instruction = upload(raw, msgId + ".xml");
        Rec parked = await("the payment with the odd purpose", () -> {
            Rec t = transactionsOf(instruction).get(msgId);
            return t != null && "REPAIR".equals(t.str("status")) ? t : null;
        });
        assertEquals("PURPOSE_NOT_ALLOWED", parked.str("reasonCode"), parked.toString());

        // ---- a payment above the clearing's limit per payment (maxAmount of the route) is parked for repair too ----
        String big = onePayment("BIG-1", "Normal Supplier", "ZAR", "FIRNZAJJ", "7700445566", "5000000.01");
        await("the payment above the route's limit to be decided", () -> !List.of("CREATED", "STAGED", "PROCESSING").contains(status(big)));
        Rec tooBig = get("/api/transactions/" + big, "operator1").body();
        assertEquals("REPAIR", tooBig.str("status"), tooBig.toString());
        assertEquals("AMOUNT_OUT_OF_RANGE", tooBig.str("reasonCode"), tooBig.toString());
        assertTrue(tooBig.str("reasonText").contains("5000000"), tooBig.str("reasonText"));
        approve(post("/api/transactions/" + parked.str("id") + "/resubmit", "operator1", Rec.of("changes", Rec.of("purposeCode", "SUPP"), "comment", "supplier payment")));
        await("the corrected payment to be routed", () -> !List.of("REPAIR", "CREATED", "PROCESSING").contains(status(parked.str("id"))));
        assertFalse("REPAIR".equals(status(parked.str("id"))));

        // ---- the kill switch: every outbound channel paused with one request, and resumed with another ----
        int outboundChannels = (int) items(get("/api/studio/models", "maker1")).stream().filter(m -> "Channel".equals(m.str("kind"))).count();
        assertEquals(403, post("/api/channels/stop-all", "viewer1", Rec.of("comment", "x")).status());
        Reply stop = post("/api/channels/stop-all", "operator1", Rec.of("comment", "a clearing incident"));
        assertEquals(0, items(get("/api/channels/paused", "operator1")).size(), "nothing stops before approval");
        Rec stopped = approve(stop);
        List<Rec> paused = items(get("/api/channels/paused", "operator1"));
        assertTrue(paused.size() >= 5 && paused.size() <= outboundChannels, paused.size() + " channels paused");
        assertTrue(paused.stream().allMatch(c -> Boolean.TRUE.equals(c.get("paused"))));
        assertTrue(Ops.list(stopped.at("result.channels")).size() == paused.size(), stopped.toString());
        approve(post("/api/channels/resume-all", "operator1", Rec.of("comment", "the incident is over")));
        assertEquals(0, items(get("/api/channels/paused", "operator1")).size());
    }

    private static String uploadQuietly(String raw) {
        try {
            return upload(raw, "repeated.xml");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void signInNew(String user, String password) throws Exception {
        PASSWORDS_OF_NEW.put(user, password);
        TOKENS.put(user, loginNew(user));
    }

    private static List<Rec> items(Reply reply) {
        List<Rec> out = new ArrayList<>();
        for (Object o : Ops.list(reply.body().get("items"))) {
            out.add(Rec.from((Map<?, ?>) o));
        }
        return out;
    }

    @Test
    @Order(13)
    void aRoleOfOnesOwnAndALimitToChannelsOrAccountsDecideWhatAUserReaches() throws Exception {
        String iso = "channels.CorporateIsoInbound";
        // ---- a role of one's own is requested, approved, and then usable ----
        assertEquals(403, post("/api/roles", "operator1", Rec.of("id", "PAYMENT_CLERK", "permissions", List.of("payments.view"))).status());
        assertEquals(400, post("/api/roles", "maker1", Rec.of("id", "clerk", "permissions", List.of("payments.view"))).status());
        assertEquals(400, post("/api/roles", "maker1", Rec.of("id", "PAYMENT_CLERK", "permissions", List.of("payments.everything"))).status());
        assertEquals(400, post("/api/roles", "maker1", Rec.of("id", "PAYMENT_CLERK", "permissions", List.of("*"))).status(), "full access is not for a role of one's own");
        assertEquals(400, post("/api/roles", "maker1", Rec.of("id", "PAYMENT_CLERK", "permissions", List.of())).status());
        assertEquals(409, post("/api/roles", "maker1", Rec.of("id", "OPERATOR", "permissions", List.of("payments.view"))).status(), "built-in roles stay as they are");
        Reply role = post("/api/roles", "maker1", Rec.of("id", "PAYMENT_CLERK", "description", "Follows and cancels payments, submits files",
                "permissions", List.of("payments.view", "payments.submit", "payments.cancel")));
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "clerkiso", "roles", List.of("PAYMENT_CLERK"), "password", "clerk-Iso-pass-2026")).status(),
                "the role does not exist before approval");
        assertEquals(403, post("/api/approvals/" + role.body().str("id") + "/approve", "maker1", null).status());
        approve(role);
        Rec listed = items(get("/api/roles", "maker1")).stream().filter(r -> "PAYMENT_CLERK".equals(r.str("id"))).findFirst().orElseThrow();
        assertEquals(false, listed.get("builtIn"));

        // ---- two limited users: one to a channel, one to an account ----
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "clerkiso", "roles", List.of("PAYMENT_CLERK"), "password", "clerk-Iso-pass-2026",
                "scope", Rec.of("channels", List.of("channels.SwiftMtOutbound")))).status(), "only inbound channels");
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "clerkacct", "roles", List.of("OPERATOR"), "password", "clerk-Acct-pass-2026",
                "scope", Rec.of("debtorAccounts", List.of("12 34")))).status());
        Reply limited = post("/api/users", "maker1", Rec.of("username", "clerkiso", "displayName", "Clerk of the ISO channel",
                "roles", List.of("PAYMENT_CLERK"), "password", "clerk-Iso-pass-2026", "scope", Rec.of("channels", List.of(iso))));
        assertTrue(limited.body().str("summary").contains("limited to channels"), "the approver sees the limit in the request");
        approve(limited);
        approve(post("/api/users", "maker1", Rec.of("username", "clerkacct", "displayName", "Clerk of one account", "roles", List.of("OPERATOR"),
                "password", "clerk-Acct-pass-2026", "scope", Rec.of("debtorAccounts", List.of("4051122334")))));
        // ---- and one limited to rand payments up to 1000 ----
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "clerkzar", "roles", List.of("OPERATOR"), "password", "clerk-Zar-pass-2026",
                "scope", Rec.of("currencies", List.of("ZA")))).status(), "a currency has three letters");
        assertEquals(400, post("/api/users", "maker1", Rec.of("username", "clerkzar", "roles", List.of("OPERATOR"), "password", "clerk-Zar-pass-2026",
                "scope", Rec.of("maxAmount", 0))).status(), "the amount must be positive");
        Reply smallScope = post("/api/users", "maker1", Rec.of("username", "clerkzar", "displayName", "Clerk of small rand payments", "roles", List.of("OPERATOR"),
                "password", "clerk-Zar-pass-2026", "scope", Rec.of("currencies", List.of("zar"), "maxAmount", 1000)));
        assertTrue(smallScope.body().str("summary").contains("currencies [ZAR] and amounts up to 1000"), smallScope.body().str("summary"));
        approve(smallScope);
        signInNew("clerkiso", "clerk-Iso-pass-2026");
        signInNew("clerkacct", "clerk-Acct-pass-2026");
        signInNew("clerkzar", "clerk-Zar-pass-2026");
        assertEquals(List.of(iso), Ops.list(get("/api/me", "clerkiso").body().at("scope.channels")));

        // ---- three payments: ISO channel and the account, MT channel and the account, ISO channel and another account ----
        String isoOwn = onePayment("SCOPE-ISO", "Normal Supplier", "ZAR", "FIRNZAJJ", "4051122334");
        String isoOther = onePayment("SCOPE-OTHER", "Normal Supplier", "ZAR", "FIRNZAJJ", "9990001112");
        String isoBig = onePayment("SCOPE-BIG", "Normal Supplier", "ZAR", "FIRNZAJJ", "9990001112", "1500.00");
        String mtMessage = upload(Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")).replace("SUPPLIERS-1004", "SCOPE-MT-1"), "scope.fin");
        String mt = await("the MT payments", () -> transactionsOf(mtMessage).values().stream().findFirst().orElse(null)).str("id");
        String isoMessage = get("/api/transactions/" + isoOwn, "operator1").body().str("instructionId");

        // the clerk of small rand payments: the 900 rand payments of any account, not the 1500 one, and no files
        List<Rec> smallOnes = items(get("/api/transactions?limit=500", "clerkzar"));
        assertTrue(smallOnes.stream().anyMatch(t -> isoOwn.equals(t.str("id"))) && smallOnes.stream().anyMatch(t -> isoOther.equals(t.str("id"))), smallOnes.toString());
        assertTrue(smallOnes.stream().noneMatch(t -> isoBig.equals(t.str("id"))));
        assertTrue(smallOnes.stream().allMatch(t -> "ZAR".equals(t.str("currency")) && Ops.num(t.get("amount")).compareTo(new java.math.BigDecimal("1000")) <= 0));
        assertEquals(200, get("/api/transactions/" + isoOwn, "clerkzar").status());
        assertEquals(404, get("/api/transactions/" + isoBig, "clerkzar").status(), "a payment above the amount does not exist for the user");
        assertEquals(404, get("/api/messages/" + isoMessage, "clerkzar").status(), "files hold any payment");
        assertEquals(403, send("POST", "/api/inbound?fileName=small.xml", "clerkzar", "text/plain", "<x/>").status());

        // the channel clerk: everything of the ISO channel, nothing of the MT channel
        List<Rec> seen = items(get("/api/transactions?limit=500", "clerkiso"));
        assertTrue(seen.stream().anyMatch(t -> isoOwn.equals(t.str("id"))) && seen.stream().anyMatch(t -> isoOther.equals(t.str("id"))));
        assertTrue(seen.stream().allMatch(t -> iso.equals(t.str("channelIn"))), "no payment of another channel is listed");
        assertEquals(200, get("/api/transactions/" + isoOwn, "clerkiso").status());
        assertEquals(404, get("/api/transactions/" + mt, "clerkiso").status(), "a payment outside the limit does not exist for the user");
        assertTrue(items(get("/api/messages?limit=500", "clerkiso")).stream().allMatch(m -> iso.equals(m.str("channel"))));
        assertEquals(200, get("/api/messages/" + isoMessage, "clerkiso").status());
        assertEquals(404, get("/api/messages/" + mtMessage, "clerkiso").status());
        assertEquals(0, items(get("/api/transactions?instructionId=" + mtMessage, "clerkiso")).size(), "not by asking for it either");
        assertEquals(404, post("/api/transactions/" + mt + "/cancel", "clerkiso", Rec.of("comment", "not mine")).status());
        // what holds everybody's payments is closed to a limited user
        for (String path : List.of("/api/outbound", "/api/statements", "/api/x/limits/4051122334")) {
            assertEquals(403, get(path, "clerkiso").status(), path);
        }
        assertEquals(403, get("/api/outbound/" + get("/api/transactions/" + isoOwn, "operator1").body().str("outboundId"), "clerkiso").status());
        Rec dashboard = get("/api/dashboard", "clerkiso").body();
        assertEquals(true, dashboard.get("scoped"));
        assertEquals(null, dashboard.get("outboundFiles"));
        // a file goes to the user's channel, and to no other
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SCOPE-SALARY-1");
        assertEquals(200, send("POST", "/api/inbound?fileName=own.xml", "clerkiso", "text/plain", raw).status());
        assertEquals(403, send("POST", "/api/inbound?channel=channels.CorporateMtInbound", "clerkiso", "text/plain", raw).status());
        Reply wrongFormat = send("POST", "/api/inbound?fileName=mt.fin", "clerkiso", "text/plain",
                Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin")).replace("SUPPLIERS-1004", "SCOPE-MT-2"));
        assertEquals("REJECTED", firstOf(wrongFormat.body(), "messages").str("status"), "an MT message cannot enter through the ISO channel of the user");

        // the account clerk: the payments of the account on any channel, no files, no submitting
        List<Rec> ofAccount = items(get("/api/transactions?limit=500", "clerkacct"));
        assertTrue(ofAccount.stream().anyMatch(t -> isoOwn.equals(t.str("id"))) && ofAccount.stream().anyMatch(t -> mt.equals(t.str("id"))));
        assertTrue(ofAccount.stream().allMatch(t -> "4051122334".equals(Ops.str(t.at("debtor.account")))));
        assertEquals(404, get("/api/transactions/" + isoOther, "clerkacct").status());
        assertEquals(0, items(get("/api/messages", "clerkacct")).size());
        assertEquals(404, get("/api/messages/" + isoMessage, "clerkacct").status());
        assertEquals(403, send("POST", "/api/inbound", "clerkacct", "text/plain", raw).status());

        // a request about a payment is seen only by those who may see the payment
        Reply own = post("/api/transactions/" + isoOwn + "/cancel", "clerkiso", Rec.of("comment", "asked by the customer"));
        assertEquals(200, own.status(), own.body().toString());
        Reply other = post("/api/transactions/" + mt + "/cancel", "operator1", Rec.of("comment", "asked by the customer"));
        assertEquals(200, other.status(), other.body().toString());
        assertEquals(200, get("/api/approvals/" + own.body().str("id"), "clerkiso").status());
        assertEquals(404, get("/api/approvals/" + other.body().str("id"), "clerkiso").status());
        assertEquals(404, post("/api/approvals/" + other.body().str("id") + "/approve", "clerkiso", null).status());
        // one request in the list is about a rejected file that never reached a channel: it is outside every limit, and must not break the list
        Reply all = get("/api/approvals?limit=500", "clerkiso");
        assertEquals(200, all.status(), all.body().toString());
        List<Rec> requests = items(all);
        assertTrue(requests.stream().anyMatch(a -> own.body().str("id").equals(a.str("id"))));
        assertTrue(requests.stream().noneMatch(a -> other.body().str("id").equals(a.str("id"))));
        assertEquals(200, get("/api/approvals/" + other.body().str("id"), "clerkacct").status(), "the account clerk may see it: the payment is of that account");

        // ---- a change of the role reaches the signed-in user at once ----
        approve(post("/api/roles", "maker1", Rec.of("id", "PAYMENT_CLERK", "description", "Follows payments", "permissions", List.of("payments.view"))));
        assertEquals(403, post("/api/transactions/" + isoOther + "/cancel", "clerkiso", Rec.of("comment", "again")).status());
        assertEquals(200, get("/api/transactions/" + isoOwn, "clerkiso").status());

        // ---- a role in use cannot be removed; taken from its users, it can ----
        assertEquals(409, post("/api/roles/PAYMENT_CLERK/removal", "maker1", new Rec()).status());
        assertEquals(409, post("/api/roles/OPERATOR/removal", "maker1", new Rec()).status());
        approve(post("/api/users", "maker1", Rec.of("username", "clerkiso", "displayName", "Clerk without a limit", "roles", List.of("OPERATOR"))));
        approve(post("/api/roles/PAYMENT_CLERK/removal", "maker1", Rec.of("comment", "end of the scenario")));
        assertTrue(items(get("/api/roles", "maker1")).stream().noneMatch(r -> "PAYMENT_CLERK".equals(r.str("id"))));
        // the change of the user ended the session and took the limit away
        TOKENS.put("clerkiso", loginNew("clerkiso"));
        assertEquals(null, get("/api/me", "clerkiso").body().get("scope"));
        assertEquals(200, get("/api/transactions/" + mt, "clerkiso").status());
        assertEquals(200, get("/api/outbound", "clerkiso").status());
    }

    private static Rec firstOf(Rec body, String list) {
        return Rec.from((java.util.Map<?, ?>) Ops.list(body.get(list)).get(0));
    }
}
