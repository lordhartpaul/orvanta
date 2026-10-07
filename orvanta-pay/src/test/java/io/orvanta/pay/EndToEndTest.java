package io.orvanta.pay;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import io.orvanta.pay.engine.Status;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.security.ApprovalService;
import io.orvanta.pay.security.AuthService.Principal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole engine in one process, on the in-memory store and bus, with the real workspace models:
 * receive, debulk, validate, enrich, screen, route, bulk, send, acknowledge, report to the customer,
 * cancel, return and recover.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndTest {

    private static final Path WORKSPACE = Path.of("..", "workspace").toAbsolutePath().normalize();

    @TempDir
    static Path data;
    static HttpServer screening;
    static OrvantaServer server;
    static DocStore store;
    static String salaryInstruction;

    @BeforeAll
    static void start() throws Exception {
        screening = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // the simulated external systems, served the way the API server serves them under /sim
        screening.createContext("/sim", exchange -> {
            byte[] reply;
            int status = 200;
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                reply = Json.write(server.simulator.handle(exchange.getRequestMethod(), exchange.getRequestURI().getPath().substring(4),
                        body.isBlank() ? new Rec() : Json.parse(body))).getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                status = 400;
                reply = Json.write(Rec.of("error", String.valueOf(e.getMessage()))).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        screening.start();
        System.setProperty("ORVANTA_SIM_URL", "http://127.0.0.1:" + screening.getAddress().getPort() + "/sim");

        store = new MemoryDocStore();
        Config config = Config.of(Rec.of(
                "units", "ingest,debulk,process,bulk,dispatch,acknowledge,cancel,return,report,recover,callback,post,reconcile",
                "recovery", Rec.of("stuckSeconds", "2", "intervalSeconds", "1", "retryBaseSeconds", "1"),
                "workspace", Rec.of("dir", WORKSPACE.toString()),
                "data", Rec.of("dir", data.toString()),
                "simulator", Rec.of("enabled", "true")));
        server = new OrvantaServer(config, store, new MemoryBus());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
        screening.stop(0);
        System.clearProperty("ORVANTA_SIM_URL");
    }

    @Test
    @Order(1)
    void isoInstructionRunsThroughEveryStageToItsFinalStatus() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml"));
        List<Rec> received = server.ingest.receive(raw, "pain001-salaries.xml", null, "test");
        assertEquals(1, received.size());
        String instructionId = received.get(0).str("id");
        assertTrue(instructionId.startsWith("ORVINS"));

        Set<String> finals = Set.of(Status.ACCEPTED, Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL, Status.REPAIR);
        await("all five transactions to reach a final status", () -> {
            List<Rec> txns = store.find(DocStore.TXN, Rec.of("instructionId", instructionId), null, false, 0);
            return txns.size() == 5 && txns.stream().allMatch(t -> finals.contains(t.str("status")));
        });

        Rec instruction = store.get(DocStore.MESSAGE, instructionId);
        assertEquals(Status.DEBULKED, instruction.str("status"));
        assertEquals("SALARY-2026-10-001", instruction.str("msgId"));
        assertEquals(5, instruction.num("transactionCount").intValue());

        Map<String, Rec> byE2e = new HashMap<>();
        for (Rec t : store.find(DocStore.TXN, Rec.of("instructionId", instructionId), null, false, 0)) {
            byE2e.put(t.str("endToEndId"), t);
        }
        assertEquals(Status.ACCEPTED, byE2e.get("SAL-0001").str("status"));
        assertEquals(Status.ACCEPTED, byE2e.get("SAL-0002").str("status"));
        assertEquals("channels.ZaRtcOutbound", byE2e.get("SAL-0001").str("route.channel"));
        assertEquals("4051122334", byE2e.get("SAL-0001").str("debtor.account"), "debtor is inherited from the batch");

        assertEquals(Status.REJECTED_BY_APPLICATION, byE2e.get("SAL-0003").str("status"));
        assertEquals("AM01", byE2e.get("SAL-0003").str("reasonCode"));

        assertEquals(Status.REJECTED_BY_APPLICATION, byE2e.get("SAL-0004").str("status"));
        assertEquals("SANC", byE2e.get("SAL-0004").str("reasonCode"));

        assertEquals(Status.REJECTED_BY_EXTERNAL, byE2e.get("SAL-0005").str("status"));
        assertEquals("AC04", byE2e.get("SAL-0005").str("reasonCode"));

        // the three routed transactions left in one pacs.008, which the clearing simulator acknowledged
        String outboundId = byE2e.get("SAL-0001").str("outboundId");
        Rec outbound = store.get(DocStore.OUTBOUND, outboundId);
        assertEquals(Status.ACKNOWLEDGED, outbound.str("status"));
        assertEquals(3, outbound.num("transactionCount").intValue());
        assertEquals(0, outbound.num("totalAmount").compareTo(new java.math.BigDecimal("41050.50")));
        assertTrue(outbound.str("payload").contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"));
        assertTrue(outbound.str("payload").contains("<NbOfTxs>3</NbOfTxs>"));
        assertTrue(Files.exists(Path.of(outbound.str("location"))));
        assertFalse(store.find(DocStore.EVENT, Rec.of("refId", byE2e.get("SAL-0001").str("id")), "at", false, 0).isEmpty());
        assertEquals("FirstRand Bank", byE2e.get("SAL-0001").str("creditor.agentName"), "enriched from the BIC directory");

        // every external system was asked, and the accepted payment stays posted against the scheme's settlement account
        Rec first = byE2e.get("SAL-0001");
        assertEquals("ACTIVE", first.str("checks.account.status"));
        assertEquals("CLEAR", first.str("checks.fraud.status"));
        assertEquals("PASS", first.str("checks.compliance.status"));
        assertEquals("OK", first.str("checks.liquidity.status"));
        assertEquals("POSTED", first.str("posting.status"));
        // rejected before posting: nothing to reverse. Rejected by the receiving side after posting: reversed
        assertEquals(null, byE2e.get("SAL-0004").get("posting"));
        String rejectedId = byE2e.get("SAL-0005").str("id");
        await("the posting of the externally rejected payment to be reversed",
                () -> "REVERSED".equals(store.get(DocStore.TXN, rejectedId).str("posting.status")));
        assertEquals("REVERSED", store.get(DocStore.TXN, rejectedId).str("posting.reversal.status"));

        // the customer is told the outcome of all five in one pain.002
        await("the customer status report", () -> "COMPLETE".equals(store.get(DocStore.MESSAGE, instructionId).str("reportState")));
        List<Rec> reports = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", instructionId), "id", false, 0);
        assertEquals(1, reports.size());
        String report = reports.get(0).str("payload");
        assertTrue(report.contains("pain.002.001.10") && report.contains("<OrgnlMsgId>SALARY-2026-10-001</OrgnlMsgId>"), report);
        assertEquals(2, count(report, "<TxSts>ACCP</TxSts>"));
        assertEquals(3, count(report, "<TxSts>RJCT</TxSts>"));
        assertTrue(report.contains("<Cd>AM01</Cd>") && report.contains("<Cd>SANC</Cd>") && report.contains("<Cd>AC04</Cd>"), report);
        salaryInstruction = instructionId;
    }

    @Test
    @Order(2)
    void aReturnOfFundsAfterAcceptanceIsRecordedAndReportedAgain() throws Exception {
        Rec txn = store.find(DocStore.TXN, Rec.of("instructionId", salaryInstruction, "endToEndId", "SAL-0001"), null, false, 0).get(0);
        server.simulator.returnFunds(txn.str("id"), "AC04", "Closed account number");
        await("the transaction to be returned", () -> Status.RETURNED.equals(store.get(DocStore.TXN, txn.str("id")).str("status")));
        Rec returned = store.get(DocStore.TXN, txn.str("id"));
        assertEquals("AC04", returned.str("reasonCode"));
        assertEquals(0, returned.num("return.amount").compareTo(new java.math.BigDecimal("18250.00")));
        await("a second status report", () -> store.count(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", salaryInstruction)) == 2);
        List<Rec> reports = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", salaryInstruction), "id", true, 0);
        String second = reports.get(0).str("payload");
        assertEquals(1, count(second, "<TxSts>"), "only the changed transaction is reported again: " + second);
        assertTrue(second.contains("Returned after acceptance."), second);
        await("the returned payment's posting to be reversed", () -> "REVERSED".equals(store.get(DocStore.TXN, txn.str("id")).str("posting.status")));
        // a second return of the same transaction changes nothing
        server.simulator.returnFunds(txn.str("id"), "AC04", null);
        await("the repeated return to be processed", () -> store.count(DocStore.MESSAGE, Rec.of("purpose", "return", "status", Status.PROCESSED)) == 2);
        assertEquals(Status.RETURNED, store.get(DocStore.TXN, txn.str("id")).str("status"));
    }

    @Test
    @Order(3)
    void aCustomerCancellationOfASentPaymentIsForwardedAndConfirmed() throws Exception {
        Rec txn = store.find(DocStore.TXN, Rec.of("instructionId", salaryInstruction, "endToEndId", "SAL-0002"), null, false, 0).get(0);
        assertEquals(Status.ACCEPTED, txn.str("status"));
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/camt055-cancel-salary.xml"));
        Rec request = server.ingest.receive(raw, "camt055.xml", null, "test").get(0);
        assertTrue(request.str("id").startsWith("ORVCXL"), request.toString());
        await("the cancellation to be confirmed by the receiving side", () -> Status.CANCELLED.equals(store.get(DocStore.TXN, txn.str("id")).str("status")));
        Rec cancelled = store.get(DocStore.TXN, txn.str("id"));
        assertEquals("ACCEPTED", cancelled.str("cancellation.status"));
        assertEquals("DUPL", cancelled.str("reasonCode"));
        Rec processed = store.get(DocStore.MESSAGE, request.str("id"));
        assertEquals(1, processed.num("forwardedCount").intValue(), processed.toString());
        Rec forwarded = store.get(DocStore.OUTBOUND, cancelled.str("cancellation.outboundId"));
        assertEquals("cancellation", forwarded.str("kind"));
        assertTrue(forwarded.str("payload").contains("camt.056.001.08") && forwarded.str("payload").contains("<OrgnlTxId>" + txn.str("id") + "</OrgnlTxId>"));

        // asking again is refused: the payment is no longer cancellable
        Rec again = server.cancellation.requestCancel(txn.str("id"), "DUPL", null, "test", null);
        assertEquals("REFUSED", again.str("outcome"), again.toString());
    }

    @Test
    @Order(4)
    void aPaymentNotYetSentIsCancelledOnTheSpotAndNeverLeaves() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-2026-10-CXL");
        String id = server.ingest.receive(raw, "to-cancel.xml", null, "test").get(0).str("id");
        await("the first transaction to wait for bulking", () -> {
            List<Rec> t = store.find(DocStore.TXN, Rec.of("instructionId", id, "endToEndId", "SAL-0001"), null, false, 0);
            return !t.isEmpty() && Status.ROUTED.equals(t.get(0).str("status"));
        });
        Rec txn = store.find(DocStore.TXN, Rec.of("instructionId", id, "endToEndId", "SAL-0001"), null, false, 0).get(0);
        Rec result = server.cancellation.requestCancel(txn.str("id"), "CUST", "Customer changed their mind", "operator1", null);
        assertEquals("CANCELLED", result.str("outcome"), result.toString());
        await("the rest of the file to finish", () -> "COMPLETE".equals(store.get(DocStore.MESSAGE, id).str("reportState")));
        Rec after = store.get(DocStore.TXN, txn.str("id"));
        assertEquals(Status.CANCELLED, after.str("status"));
        assertEquals(null, after.str("outboundId"), "a cancelled payment is in no outbound file");
        String report = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", id), "id", false, 0).get(0).str("payload");
        assertEquals(1, count(report, "<TxSts>CANC</TxSts>"), report);
    }

    @Test
    @Order(5)
    void interruptedWorkIsPickedUpAgainAndFailedCallsAreRetried() throws Exception {
        Rec template = store.find(DocStore.TXN, Rec.of("instructionId", salaryInstruction, "endToEndId", "SAL-0002"), null, false, 0).get(0);
        String longAgo = java.time.Instant.now().minusSeconds(600).toString();
        for (String key : List.of("outboundId", "cancellation", "posting", "route", "screening", "acknowledgementId", "reasonCode", "reasonText", "reportedStatus")) {
            template.remove(key);
        }
        template.put("instructionId", "none");

        // a transaction whose processing died half way
        Rec stuck = template.copy();
        stuck.put("id", "ORVTXN9000000001");
        stuck.put("status", Status.PROCESSING);
        stuck.put("updatedAt", longAgo);
        store.insert(DocStore.TXN, stuck);
        // a transaction parked by a failed external call, with its retry time reached
        Rec parked = template.copy();
        parked.put("id", "ORVTXN9000000002");
        parked.put("status", Status.REPAIR);
        parked.put("reasonCode", "CONNECTOR_ERROR");
        parked.put("retry", Rec.of("count", 1, "nextAt", longAgo));
        parked.put("updatedAt", longAgo);
        store.insert(DocStore.TXN, parked);

        await("both to be processed to the end", () -> Status.ACCEPTED.equals(store.get(DocStore.TXN, "ORVTXN9000000001").str("status"))
                && Status.ACCEPTED.equals(store.get(DocStore.TXN, "ORVTXN9000000002").str("status")));
        assertTrue(store.find(DocStore.EVENT, Rec.of("refId", "ORVTXN9000000001", "type", "RECOVERED"), null, false, 0).size() == 1);
        assertEquals(null, store.get(DocStore.TXN, "ORVTXN9000000002").str("retry.nextAt"));
    }

    private String submitOne(String msgId, String creditorName) throws Exception {
        return submitOne(msgId, creditorName, "ZAR", "FIRNZAJJ");
    }

    private String submitOne(String msgId, String creditorName, String currency, String creditorBic) throws Exception {
        String raw = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\"><CstmrCdtTrfInitn><GrpHdr><MsgId>" + msgId
                + "</MsgId><CreDtTm>2026-10-04T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>" + msgId + "-1</PmtInfId>"
                + "<ReqdExctnDt><Dt>2026-10-05</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>"
                + "<DbtrAcct><Id><Othr><Id>4051122334</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>" + msgId + "</EndToEndId></PmtId>"
                + "<Amt><InstdAmt Ccy=\"" + currency + "\">900.00</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>" + creditorBic + "</BICFI></FinInstnId></CdtrAgt>"
                + "<Cdtr><Nm>" + creditorName + "</Nm></Cdtr><CdtrAcct><Id><Othr><Id>62011223344</Id></Othr></Id></CdtrAcct>"
                + "</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>";
        String instruction = server.ingest.receive(raw, msgId + ".xml", null, "test").get(0).str("id");
        await("the transaction of " + msgId, () -> store.count(DocStore.TXN, Rec.of("instructionId", instruction)) == 1);
        return store.find(DocStore.TXN, Rec.of("instructionId", instruction), null, false, 0).get(0).str("id");
    }

    private String statusOf(String txnId) {
        return store.get(DocStore.TXN, txnId).str("status");
    }

    @Test
    @Order(6)
    void aFraudAlertIsHeldUntilAReviewerReleasesOrRejectsIt() throws Exception {
        String released = submitOne("HOLD-RELEASE", "FRAUDCHECK Supplies");
        String rejected = submitOne("HOLD-REJECT", "FRAUDCHECK Trading");
        await("both to be held", () -> Status.HELD.equals(statusOf(released)) && Status.HELD.equals(statusOf(rejected)));
        assertEquals("FRAUD", store.get(DocStore.TXN, released).str("hold.code"));
        assertEquals(null, store.get(DocStore.TXN, released).get("posting"), "nothing is posted while a payment is held");

        assertTrue(server.review.release(released, "checker1", "known supplier"));
        await("the released payment to be accepted", () -> Status.ACCEPTED.equals(statusOf(released)));
        Rec after = store.get(DocStore.TXN, released);
        assertEquals(List.of("FRAUD"), after.get("overrides"));
        assertEquals("POSTED", after.str("posting.status"));
        assertFalse(server.review.release(released, "checker1", null), "a payment is released once");

        assertTrue(server.review.reject(rejected, "checker1", "not a known supplier"));
        assertEquals(Status.REJECTED_BY_APPLICATION, statusOf(rejected));
        assertEquals("FRAUD", store.get(DocStore.TXN, rejected).str("reasonCode"));
    }

    @Test
    @Order(6)
    void aLateAnswerAndAShortageOfLiquidityOnlyDelayThePayment() throws Exception {
        int postingsBefore = server.simulator.postingCount();
        String slow = submitOne("WAIT-FRAUD", "SLOWCHECK Logistics");
        String dry = submitOne("WAIT-LIQUIDITY", "NOLIQUIDITY Partner");
        await("the slow fraud check to leave the payment waiting", () -> Status.WAITING.equals(statusOf(slow)) || Status.ACCEPTED.equals(statusOf(slow)));
        await("both to be accepted in the end", () -> Status.ACCEPTED.equals(statusOf(slow)) && Status.ACCEPTED.equals(statusOf(dry)));

        Rec answered = store.get(DocStore.TXN, slow);
        assertEquals("CLEAR", answered.str("checks.fraud.status"));
        assertTrue(store.find(DocStore.EVENT, Rec.of("refId", slow, "type", "ANSWER_RECEIVED"), null, false, 0).size() == 1);
        assertEquals(1, store.count(DocStore.MESSAGE, Rec.of("purpose", "callback", "status", Status.PROCESSED)));

        Rec funded = store.get(DocStore.TXN, dry);
        assertEquals(3, funded.num("checks.liquidity.requests").intValue(), "asked again until the route was funded");
        assertEquals(null, funded.get("wait"));
        // the flow ran several times for each of the two, yet each was posted exactly once
        assertEquals(postingsBefore + 2, server.simulator.postingCount());

        // a callback for a payment that is not waiting changes nothing
        server.ingest.receive("{\"messageType\":\"fraud.result\",\"transactionId\":\"" + slow + "\",\"status\":\"REVIEW\"}", "late.json", null, "test");
        await("the stray callback to be processed", () -> store.count(DocStore.MESSAGE, Rec.of("purpose", "callback", "status", Status.PROCESSED)) == 2);
        assertEquals("CLEAR", store.get(DocStore.TXN, slow).str("checks.fraud.status"));
    }

    @Test
    @Order(7)
    void anEventWhoseHandlerKeepsFailingIsKeptAsADeadLetter() throws Exception {
        server.platform.bus.subscribe("orv.test.failing", "test", m -> {
            throw new IllegalStateException("handler is broken");
        });
        server.platform.bus.publish("orv.test.failing", Rec.of("id", "X1"));
        await("the dead letter", () -> store.count(DocStore.DEAD_LETTER, Rec.of("topic", "orv.test.failing", "status", "OPEN")) == 1);
        Rec letter = store.find(DocStore.DEAD_LETTER, Rec.of("topic", "orv.test.failing"), null, false, 0).get(0);
        assertEquals("X1", letter.str("refId"));
        assertTrue(letter.str("error").contains("handler is broken"));
    }

    @Test
    @Order(8)
    void theSameFileAgainIsRejectedAsADuplicate() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml"));
        String id = server.ingest.receive(raw, "again.xml", null, "test").get(0).str("id");
        await("the duplicate to be rejected", () -> Status.REJECTED.equals(store.get(DocStore.MESSAGE, id).str("status")));
        assertEquals("AM05", store.get(DocStore.MESSAGE, id).str("reasonCode"));
        assertEquals(0, store.count(DocStore.TXN, Rec.of("instructionId", id)));
        // the customer is told that the whole file was rejected
        await("the rejection report", () -> store.count(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", id)) == 1);
        String report = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", id), null, false, 0).get(0).str("payload");
        assertTrue(report.contains("<GrpSts>RJCT</GrpSts>") && report.contains("<Cd>AM05</Cd>"), report);
    }

    @Test
    @Order(8)
    void mt101IsDebulkedAndLeavesAsMt103() throws Exception {
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin"));
        String id = server.ingest.receive(raw, "mt101.fin", null, "test").get(0).str("id");
        await("both transactions to be sent", () -> store.count(DocStore.TXN, Rec.of("instructionId", id, "status", Status.SENT)) == 2);
        List<Rec> txns = store.find(DocStore.TXN, Rec.of("instructionId", id), "id", false, 0);
        assertEquals("channels.SwiftMtOutbound", txns.get(0).str("route.channel"));
        // one bulk per currency: USD and EUR leave in separate files
        Rec usd = store.get(DocStore.OUTBOUND, txns.get(0).str("outboundId"));
        assertEquals(Status.SENT, usd.str("status"));
        assertTrue(usd.str("payload").startsWith("{1:F01ORVAZAJJAXXX0000000000}{2:I103CHASUS33AXXXN}"), usd.str("payload"));
        assertTrue(usd.str("payload").contains(":20:" + txns.get(0).str("id")));
        assertTrue(usd.str("payload").contains("USD12500,"));
    }

    @Test
    @Order(8)
    void unreadableAndUnroutablePayloadsAreStoredAsRejectedNotDropped() {
        Rec garbage = server.ingest.receive("this is not a payment file", "x.txt", null, "test").get(0);
        assertEquals(Status.REJECTED, garbage.str("status"));
        assertEquals("PARSE_ERROR", garbage.str("reasonCode"));
        Rec unknownType = server.ingest.receive(
                "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:acmt.023.001.03\"><IdVrfctnReq/></Document>", "stmt.xml", null, "test").get(0);
        assertEquals("NO_CHANNEL", unknownType.str("reasonCode"));
        assertNotNull(store.get(DocStore.MESSAGE, unknownType.str("id")).str("raw"));
    }

    @Test
    @Order(40)
    void aFolderWithChecksumCompanionsTakesAFileOnlyWhenItsChecksumAgrees() throws Exception {
        Path dir = data.resolve("inbound/checksummed");
        Files.createDirectories(dir.resolve("archive"));
        Rec transport = Rec.of("type", "folder", "path", "inbound/checksummed", "checksum", "sha256");
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-SUM-1");
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // a file without its checksum file waits for it
        Files.write(dir.resolve("good.xml"), bytes);
        server.ingest.pollFolder(dir, "channels.CorporateIsoInbound", "test", transport);
        assertTrue(Files.exists(dir.resolve("good.xml")), "left in place until the checksum file arrives");
        assertEquals(0, store.count(DocStore.MESSAGE, Rec.of("fileName", "good.xml")));
        // with the checksum file, it is taken, and both are archived
        Files.writeString(dir.resolve("good.xml.sha256"), io.orvanta.pay.transport.Checksums.companionText(io.orvanta.pay.transport.Checksums.sha256Hex(bytes), "good.xml"));
        server.ingest.pollFolder(dir, "channels.CorporateIsoInbound", "test", transport);
        Rec taken = store.find(DocStore.MESSAGE, Rec.of("fileName", "good.xml"), null, false, 0).get(0);
        assertFalse(Status.REJECTED.equals(taken.str("status")), taken.toString());
        assertTrue(Files.exists(dir.resolve("archive/good.xml")) && Files.exists(dir.resolve("archive/good.xml.sha256")));
        // a checksum that does not agree: refused without the file being read as a message, and seen
        Files.write(dir.resolve("bad.xml"), raw.replace("SALARY-SUM-1", "SALARY-SUM-2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("bad.xml.sha256"), "0".repeat(64) + "  bad.xml\n");
        server.ingest.pollFolder(dir, "channels.CorporateIsoInbound", "test", transport);
        Rec refused = store.find(DocStore.MESSAGE, Rec.of("fileName", "bad.xml"), null, false, 0).get(0);
        assertEquals(Status.REJECTED, refused.str("status"));
        assertEquals("CHECKSUM_MISMATCH", refused.str("reasonCode"));
        assertEquals("", refused.str("raw"), "the content of a file that failed its check is not kept");
        assertFalse(Files.exists(dir.resolve("bad.xml")) || Files.exists(dir.resolve("bad.xml.sha256")));
        // the checksum file's format is the one of sha256sum, so a partner can make and check it with standard tools
        assertEquals(null, io.orvanta.pay.transport.Checksums.verify(bytes, io.orvanta.pay.transport.Checksums.sha256Hex(bytes).toUpperCase() + " *good.xml"));
        assertTrue(io.orvanta.pay.transport.Checksums.verify(bytes, "not a checksum").contains("does not hold"));
    }

    @Test
    @Order(41)
    void aFixedWidthPaymentFileBecomesAnInstructionAndItsPayments() throws Exception {
        String file = "H" + pad("FLAT-E2E-1", 16) + "20261006" + pad("Karoo Mining Supplies", 35) + pad("4051122334", 20) + "\r\n"
                + "D" + pad("FLAT-E2E-1-1", 16) + "000001825000" + "ZAR" + pad("Thandiwe Mokoena", 35) + pad("62011223344", 20) + pad("FIRNZAJJ", 11) + pad("Salary October", 40) + "\r\n"
                + "D" + pad("FLAT-E2E-1-2", 16) + "000000009950" + "ZAR" + pad("Pieter van der Merwe", 35) + pad("62055667788", 20) + pad("ABSAZAJJ", 11) + pad("", 40) + "\r\n";
        // without the channel the file is not understood; for its channel it is read by the Format model
        Rec loose = server.ingest.receive(file, "payments.txt", null, "test").get(0);
        assertEquals("PARSE_ERROR", loose.str("reasonCode"));
        Rec received = server.ingest.receive(file, "payments.txt", "channels.FlatFileInbound", "test").get(0);
        assertFalse(Status.REJECTED.equals(received.str("status")), received.toString());
        assertEquals("formats.PaymentsFixed", received.str("messageType"));
        assertEquals("flat", received.str("format"));
        await("the two payments of the flat file", () -> store.count(DocStore.TXN, Rec.of("instructionId", received.str("id"))) == 2);
        Rec one = store.find(DocStore.TXN, Rec.of("instructionId", received.str("id"), "endToEndId", "FLAT-E2E-1-1"), null, false, 1).get(0);
        assertEquals(0, new java.math.BigDecimal("18250.00").compareTo(io.orvanta.core.expr.Ops.num(one.get("amount"))));
        assertEquals("Thandiwe Mokoena", one.at("creditor.name"));
        assertEquals("4051122334", one.at("debtor.account"));
        // a line the model does not know is refused before anything is read
        Rec broken = server.ingest.receive(file + "Z\r\n", "broken.txt", "channels.FlatFileInbound", "test").get(0);
        assertEquals("PARSE_ERROR", broken.str("reasonCode"));
        assertTrue(broken.str("reasonText").contains("line 4"), broken.str("reasonText"));
    }

    private static String pad(String s, int n) {
        return s.length() >= n ? s.substring(0, n) : s + " ".repeat(n - s.length());
    }

    @Test
    @Order(42)
    void aScheduledJobRunsOnceWhenItsMinuteComesAndCanBeRunByHand() throws Exception {
        // the shipped schedules are listed with their next run; they are off in a test, so the tick runs nothing
        List<Rec> listed = server.schedules.list();
        assertTrue(listed.stream().anyMatch(s -> "schedules.CloseDay".equals(s.str("name")) && "closeDay".equals(s.str("job")) && s.str("nextAt") != null), listed.toString());
        assertEquals(0, server.schedules.tick(java.time.Instant.now()));
        // a schedule that is due now: deployed as an overlay would be, here handed in directly; the minute is claimed once
        Rec due = Rec.of("kind", "Schedule", "name", "schedules.TestReports", "cron", "* * * * *", "job", "accountReports", "enabled", true);
        Rec ran = server.schedules.run(due, "test");
        assertEquals("DONE", ran.str("lastOutcome"), ran.toString());
        assertEquals(1, io.orvanta.core.expr.Ops.num(ran.get("runs")).intValue());
        assertNotNull(store.get(DocStore.SCHEDULE, "schedules.TestReports").str("lastRunAt"));
        // a job that fails is recorded as such and does not stop the others
        Rec failing = Rec.of("kind", "Schedule", "name", "schedules.TestStatement", "cron", "* * * * *", "job", "statement", "channel", "channels.CustomerStatementOutbound", "account", "NO-SUCH-ACCOUNT", "enabled", true);
        Rec failed = server.schedules.run(failing, "test");
        assertEquals("FAILED", failed.str("lastOutcome"), failed.toString());
        assertNotNull(failed.str("lastProblem"));
        // the minute claim: the same minute is not run twice across processes
        assertTrue(store.insertIfAbsent(DocStore.SCHEDULE_RUN, Rec.of("id", "schedules.X|2026-10-07T18:00", "schedule", "schedules.X")));
        assertFalse(store.insertIfAbsent(DocStore.SCHEDULE_RUN, Rec.of("id", "schedules.X|2026-10-07T18:00", "schedule", "schedules.X")));
    }

    @Test
    @Order(9)
    void aChannelFolderAcceptsOnlyWhatItsChannelAccepts() throws Exception {
        Path folder = data.resolve("inbound/corporate-iso");
        await("the channel folder to be watched", () -> Files.isDirectory(folder.resolve("archive")));
        assertTrue(server.transports.listening().containsKey("channels.CorporateIsoInbound"));
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-FOLDER-1");
        Files.writeString(folder.resolve("salaries.tmp"), raw);
        Files.move(folder.resolve("salaries.tmp"), folder.resolve("salaries.xml"));
        Files.copy(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin"), folder.resolve("wrong-channel.fin"));

        await("both files to be taken", () -> store.count(DocStore.MESSAGE, Rec.of("receivedBy", "folder:inbound/corporate-iso")) == 2);
        Rec accepted = store.find(DocStore.MESSAGE, Rec.of("fileName", "salaries.xml"), null, false, 0).get(0);
        assertEquals("channels.CorporateIsoInbound", accepted.str("channel"));
        Rec refused = store.find(DocStore.MESSAGE, Rec.of("fileName", "wrong-channel.fin"), null, false, 0).get(0);
        assertEquals(Status.REJECTED, refused.str("status"));
        assertEquals("NO_CHANNEL", refused.str("reasonCode"), "an MT101 dropped into the pain.001 channel's folder is not passed to another channel");
        assertTrue(Files.exists(folder.resolve("archive/salaries.xml")));
    }

    @Test
    @Order(9)
    void aSepaFileUsesTheBulkAndTheInstantRailAndWaitsOutsideOpeningHours() throws Exception {
        // Monday 10:00 in Berlin: SEPA Credit Transfer is open
        io.orvanta.core.expr.Time.setGlobal(java.time.Instant.parse("2026-10-05T08:00:00Z"));
        try {
            String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-sepa.xml"));
            String id = server.ingest.receive(raw, "sepa.xml", null, "test").get(0).str("id");
            Set<String> finals = Set.of(Status.ACCEPTED, Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL, Status.REPAIR);
            await("the five SEPA transactions to finish", () -> {
                List<Rec> txns = store.find(DocStore.TXN, Rec.of("instructionId", id), null, false, 0);
                return txns.size() == 5 && txns.stream().allMatch(t -> finals.contains(t.str("status")));
            });
            Map<String, Rec> byE2e = new HashMap<>();
            for (Rec t : store.find(DocStore.TXN, Rec.of("instructionId", id), null, false, 0)) {
                byE2e.put(t.str("endToEndId"), t);
            }
            // the bulk rail: two payments in one file
            Rec first = byE2e.get("SEPA-0001");
            assertEquals(Status.ACCEPTED, first.str("status"), first.str("reasonText"));
            assertEquals("SEPA-SCT", first.str("route.scheme"));
            assertEquals(first.str("outboundId"), byE2e.get("SEPA-0002").str("outboundId"));
            Rec bulk = store.get(DocStore.OUTBOUND, first.str("outboundId"));
            assertEquals(2, bulk.num("transactionCount").intValue());
            assertTrue(bulk.str("payload").contains("<SvcLvl>") && !bulk.str("payload").contains("<LclInstrm>"), bulk.str("payload"));
            assertTrue(bulk.str("location").contains("sepa-sct"));
            // a euro account is debited in euro: no conversion
            assertEquals("EUR", first.str("posting.currency"));
            assertEquals(null, first.get("fx"));
            // the instant rail: one payment, one message, marked INST
            Rec instant = byE2e.get("SEPA-0004");
            assertEquals(Status.ACCEPTED, instant.str("status"), instant.str("reasonText"));
            assertEquals("SEPA-INST", instant.str("route.scheme"));
            Rec single = store.get(DocStore.OUTBOUND, instant.str("outboundId"));
            assertEquals(1, single.num("transactionCount").intValue());
            assertTrue(single.str("payload").contains("<Cd>INST</Cd>") && single.str("payload").contains("<AccptncDtTm>"), single.str("payload"));
            // the scheme rules
            assertEquals("CH16", byE2e.get("SEPA-0003").str("reasonCode"));
            assertEquals("AM02", byE2e.get("SEPA-0005").str("reasonCode"));

            // Sunday: the bulk rail is closed, the instant rail is not
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.parse("2026-10-11T10:00:00Z"));
            String again = raw.replace("SEPA-2026-10-001", "SEPA-2026-10-002").replace("SEPA-000", "SEPB-000");
            String second = server.ingest.receive(again, "sepa-sunday.xml", null, "test").get(0).str("id");
            await("the Sunday file: bulk payments warehoused, instant payment accepted", () -> {
                List<Rec> txns = store.find(DocStore.TXN, Rec.of("instructionId", second), null, false, 0);
                return txns.size() == 5 && txns.stream().filter(t -> Status.WAREHOUSED.equals(t.str("status"))).count() == 2
                        && txns.stream().anyMatch(t -> "SEPB-0004".equals(t.str("endToEndId")) && Status.ACCEPTED.equals(t.str("status")));
            });
            Rec waiting = store.find(DocStore.TXN, Rec.of("instructionId", second, "endToEndId", "SEPB-0001"), null, false, 0).get(0);
            assertEquals("2026-10-12T05:00:00Z", waiting.str("warehouse.until"));
            assertEquals(null, waiting.get("posting"), "nothing is posted while a payment is warehoused");

            // Monday 08:00 in Berlin: the warehouse releases them
            io.orvanta.core.expr.Time.setGlobal(java.time.Instant.parse("2026-10-12T06:00:00Z"));
            await("the warehoused payments to be sent and accepted",
                    () -> store.count(DocStore.TXN, Rec.of("instructionId", second, "status", Status.ACCEPTED)) == 3);
            Rec released = store.get(DocStore.TXN, waiting.str("id"));
            assertEquals("2026-10-12", released.str("valueDate"));
            assertEquals(null, released.get("warehouse"));
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
        }
    }

    @Test
    @Order(9)
    void swiftPaymentsSettleByCoverOrSerialAndTheStatementConfirmsThem() throws Exception {
        String cover = submitOne("XB-COVER", "Overseas Supplier Inc", "USD", "CITIUS33");
        String serial = submitOne("XB-SERIAL", "Small Town Trading", "USD", "SMALUS44");
        await("both to be sent", () -> Status.SENT.equals(statusOf(cover)) && Status.SENT.equals(statusOf(serial)));

        Rec coverTxn = store.get(DocStore.TXN, cover);
        assertEquals("COVER", coverTxn.str("route.settlementMethod"));
        String file = store.get(DocStore.OUTBOUND, coverTxn.str("outboundId")).str("payload");
        // cover: the customer transfer goes to the creditor's bank, the funds through our dollar correspondent
        assertTrue(file.contains("{2:I103CITIUS33AXXXN}") && file.contains(":53A:CHASUS33"), file);
        assertTrue(file.contains("{2:I202CHASUS33AXXXN}") && file.contains("{119:COV}") && file.contains(":21:" + cover) && file.contains(":58A:CITIUS33"), file);
        // serial: one message to the correspondent, naming the creditor's bank
        Rec serialTxn = store.get(DocStore.TXN, serial);
        assertEquals("SERIAL", serialTxn.str("route.settlementMethod"));
        String serialFile = store.get(DocStore.OUTBOUND, serialTxn.str("outboundId")).str("payload");
        assertTrue(serialFile.contains("{2:I103CHASUS33AXXXN}") && serialFile.contains(":57A:SMALUS44"), serialFile);

        // the correspondent's statement shows the debits: the payments are reconciled and count as settled
        assertTrue(server.simulator.nostroStatement("USD") >= 3);
        await("both to be settled by the statement", () -> Status.ACCEPTED.equals(statusOf(cover)) && Status.ACCEPTED.equals(statusOf(serial)));
        Rec settled = store.get(DocStore.TXN, cover);
        assertEquals("MATCHED", settled.str("reconciliation.status"));
        assertEquals("STATEMENT", settled.str("externalStatus"));
        Rec statement = store.get(DocStore.STATEMENT, settled.str("reconciliation.statementId"));
        assertEquals("OK", statement.str("balanceCheck"));
        assertEquals("NOSTRO-USD", statement.str("account"));
        await("the statement to be finished", () -> Status.PROCESSED.equals(store.get(DocStore.MESSAGE, statement.str("messageId")).str("status")));
        Rec message = store.get(DocStore.MESSAGE, statement.str("messageId"));
        assertEquals(1, message.num("unmatchedCount").intValue(), "the account charge matches no payment");
        assertTrue(message.num("matchedCount").intValue() >= 2);

        // an MT940 whose line disagrees with the payment, and whose balances do not add up
        String mt940 = "{1:F01ORVAZAJJAXXX0000000000}{2:O9400800261005CHASUS33AXXX00000000002610050800N}{4:\r\n:20:STMT-X\r\n:25:NOSTRO-USD\r\n:28C:279/1\r\n"
                + ":60F:C261005USD1000,00\r\n:61:2610051005D1,00NTRF" + serial + "//REF1\r\n:86:Wrong amount\r\n:62F:C261005USD500,00\r\n-}";
        String id = server.ingest.receive(mt940, "stmt.fin", null, "test").get(0).str("id");
        assertTrue(id.startsWith("ORVSTM"), id);
        await("the MT940 to be processed", () -> Status.PROCESSED.equals(store.get(DocStore.MESSAGE, id).str("status")));
        Rec wrong = store.get(DocStore.STATEMENT, id + "-1");
        assertEquals("FAILED", wrong.str("balanceCheck"));
        assertEquals(1, store.get(DocStore.MESSAGE, id).num("mismatchedCount").intValue());
        assertEquals(1, store.find(DocStore.EVENT, Rec.of("refId", serial, "type", "RECONCILIATION_MISMATCH"), null, false, 0).size());
        assertEquals(Status.ACCEPTED, statusOf(serial), "a mismatch changes nothing on the payment");
    }

    private void mandate(String id, String debtorAccount, String status, int collections) {
        server.platform.data.save("data.Mandates", Rec.of("mandateId", id, "creditorId", "DE98ZZZ09999999999",
                "creditorName", "Nordlicht Energie GmbH", "debtorAccount", debtorAccount, "type", "RCUR", "status", status,
                "collections", collections), false);
    }

    @Test
    @Order(9)
    void directDebitsAreCollectedUnderTheirMandatesAndCanBeRefused() throws Exception {
        mandate("MND-1001", "FR1420041010050500013M02606", "ACTIVE", 3);
        mandate("MND-1002", "NL91ABNA0417164300", "ACTIVE", 1);
        mandate("MND-1004", "IT60X0542811101000000123456", "CANCELLED", 5);
        mandate("MND-1005", "DE89370400440532013000", "ACTIVE", 2);
        // Tuesday 10:00 in Berlin: the business day before the Wednesday collection date, before the cut-off
        io.orvanta.core.expr.Time.setGlobal(java.time.Instant.parse("2026-10-06T08:00:00Z"));
        try {
            String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain008-collections.xml"));
            Rec received = server.ingest.receive(raw, "collections.xml", null, "test").get(0);
            assertEquals("rails.sepa.SddInbound", received.str("channel"));
            String id = received.str("id");
            Set<String> finals = Set.of(Status.ACCEPTED, Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL, Status.REPAIR);
            await("the five collections to finish", () -> {
                List<Rec> txns = store.find(DocStore.TXN, Rec.of("instructionId", id), null, false, 0);
                return txns.size() == 5 && txns.stream().allMatch(t -> finals.contains(t.str("status")));
            });
            Map<String, Rec> byE2e = new HashMap<>();
            for (Rec t : store.find(DocStore.TXN, Rec.of("instructionId", id), null, false, 0)) {
                byE2e.put(t.str("endToEndId"), t);
            }
            Rec collected = byE2e.get("DD-0001");
            assertEquals(Status.ACCEPTED, collected.str("status"), collected.str("reasonText"));
            assertEquals("DD", collected.str("paymentType"));
            assertEquals("SEPA-SDD", collected.str("route.scheme"));
            assertEquals("Nordlicht Energie GmbH", collected.str("creditor.name"), "the creditor comes from the batch");
            assertEquals("2026-10-07", collected.str("collectionDate"));
            // the debtor's bank refused one; the mandate checks refused three
            assertEquals(Status.REJECTED_BY_EXTERNAL, byE2e.get("DD-0002").str("status"));
            assertEquals("MD01", byE2e.get("DD-0003").str("reasonCode"), "no mandate");
            assertEquals("MD01", byE2e.get("DD-0004").str("reasonCode"), "cancelled mandate");
            assertEquals("MD02", byE2e.get("DD-0005").str("reasonCode"), "another account than the mandate names");

            Rec file = store.get(DocStore.OUTBOUND, collected.str("outboundId"));
            assertEquals("pacs.003.001.08", file.str("messageType"));
            assertEquals(2, file.num("transactionCount").intValue());
            assertTrue(file.str("payload").contains("<MndtId>MND-1001</MndtId>") && file.str("payload").contains("<SeqTp>RCUR</SeqTp>")
                    && file.str("payload").contains("<ReqdColltnDt>2026-10-07</ReqdColltnDt>"), file.str("payload"));

            Rec counted = server.platform.data.find("data.Mandates", Rec.of("mandateId", "MND-1001"), null, false, 1).get(0);
            assertEquals(4, counted.num("collections").intValue());
            assertEquals(collected.str("id"), counted.str("lastTransactionId"));
            Rec untouched = server.platform.data.find("data.Mandates", Rec.of("mandateId", "MND-1004"), null, false, 1).get(0);
            assertEquals(5, untouched.num("collections").intValue(), "a refused collection is not counted");

            // within eight weeks the debtor may ask for the money back, without giving a reason
            server.simulator.returnFunds(collected.str("id"), "MD06", "Refund requested by the debtor");
            await("the refund to be recorded", () -> Status.RETURNED.equals(statusOf(collected.str("id"))));
            assertEquals("MD06", store.get(DocStore.TXN, collected.str("id")).str("reasonCode"));
            await("the creditor to be told about the refund",
                    () -> store.count(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "instructionId", id)) >= 2);
        } finally {
            io.orvanta.core.expr.Time.setGlobal(null);
        }
    }

    @Test
    @Order(10)
    void aModelChangeNeedsASecondPersonAndThenChangesBehaviour() throws Exception {
        ApprovalService approvals = server.approvals;
        approvals.register("MODEL_CHANGE", "studio.approve", approval -> {
            Rec payload = approval.rec("payload");
            Rec deployment = server.platform.deployments.deploy(server.platform.deployments.overlay(payload.str("path"), payload.str("text")),
                    approval.str("maker"), approval.str("checker"), "test");
            return Rec.of("deploymentId", deployment.str("id"));
        });
        String before = server.platform.deployments.activeId();
        String text = Files.readString(WORKSPACE.resolve("payments/routing/OutboundRouting.yaml"))
                .replace("when: txn.currency == 'ZAR'", "when: txn.currency == 'ZAR' and txn.amount < 1000");
        Rec request = approvals.request("MODEL_CHANGE", "DecisionTable payments.routing.OutboundRouting",
                Rec.of("path", "payments/routing/OutboundRouting.yaml", "text", text), "maker1", "limit domestic route");

        Principal maker = new Principal("maker1", "Maker", Set.of(), Set.of("*"));
        Principal noPermission = new Principal("someone", "Someone", Set.of(), Set.of("payments.view"));
        Principal checker = new Principal("checker1", "Checker", Set.of(), Set.of("studio.approve"));
        assertThrows(SecurityException.class, () -> approvals.decide(request.str("id"), true, maker, null), "maker cannot approve own request");
        assertThrows(SecurityException.class, () -> approvals.decide(request.str("id"), true, noPermission, null));
        assertEquals(before, server.platform.deployments.activeId(), "nothing is deployed before approval");

        Rec decided = approvals.decide(request.str("id"), true, checker, "ok");
        assertEquals(ApprovalService.APPROVED, decided.str("status"), String.valueOf(decided.get("failure")));
        assertFalse(before.equals(server.platform.deployments.activeId()));
        assertThrows(IllegalStateException.class, () -> approvals.decide(request.str("id"), true, checker, null), "a request is decided once");

        // a rand payment of 18250 no longer matches the domestic row; with a creditor agent it now goes to SWIFT
        String raw = Files.readString(WORKSPACE.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", "SALARY-2026-10-002");
        String id = server.ingest.receive(raw, "after-change.xml", null, "test").get(0).str("id");
        await("the first transaction to be routed by the new table", () -> {
            List<Rec> t = store.find(DocStore.TXN, Rec.of("instructionId", id, "endToEndId", "SAL-0001"), null, false, 0);
            return !t.isEmpty() && t.get(0).at("route.channel") != null;
        });
        Rec txn = store.find(DocStore.TXN, Rec.of("instructionId", id, "endToEndId", "SAL-0001"), null, false, 0).get(0);
        assertEquals("channels.SwiftMtOutbound", txn.str("route.channel"));
        assertEquals(server.platform.deployments.activeId(), txn.str("deployment"));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
