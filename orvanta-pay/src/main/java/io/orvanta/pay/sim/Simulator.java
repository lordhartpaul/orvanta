package io.orvanta.pay.sim;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.format.IsoXml;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-ins for the external systems, for development, tests and demonstrations only. Each
 * behaves normally unless a magic word asks for a particular outcome:
 *
 * <pre>
 * sanctions screening   creditor name contains BLOCKED: hit; REVIEWME: possible match
 * account lookup        account starts with 000: unknown; 999: closed; 666: blocked
 * fraud check           creditor name contains FRAUDCHECK: review; SLOWCHECK: pending, answered 2 seconds later
 * compliance check      remittance contains COMPLIANCE: review; 50000 or more in a currency other than rand or euro: document required
 * FX rates              rand per unit of USD, EUR and GBP; any other currency is not quoted
 * liquidity check       creditor name contains NOLIQUIDITY: insufficient on the first two requests
 * account posting       posts and reverses; the same idempotency key always gives the same posting
 * clearing              answers payment files with pacs.002 (creditor name REJECTME: rejected AC04) and
 *                       cancellation requests with camt.029 (NOCANCEL: refused); returns funds on demand
 * </pre>
 *
 * Request and reply are JSON over HTTP under /sim; late answers and clearing messages are files
 * dropped into the inbound folder, exactly as a real system would deliver them.
 */
public final class Simulator {

    private static final Logger LOG = LoggerFactory.getLogger(Simulator.class);
    private static final Map<String, String> RATES = Map.of("USD", "18.50", "EUR", "20.10", "GBP", "23.40");

    private final Platform platform;
    private final Map<String, Rec> postings = new ConcurrentHashMap<>();
    private final Map<String, Integer> liquidityRequests = new ConcurrentHashMap<>();
    private final AtomicLong postingNumber = new AtomicLong();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "orv-simulator");
        t.setDaemon(true);
        return t;
    });

    public Simulator(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.OUTBOUND_SENT, "simulator", m -> answer(m.str("id")));
        // a real clearing system answers because the file reached it, not because we announced it: a file that was
        // delivered by a process that died before announcing it is answered from this sweep
        timer.scheduleWithFixedDelay(() -> {
            try {
                for (Rec outbound : platform.store.find(DocStore.OUTBOUND, Rec.of("status", "SENT"), "id", false, 500)) {
                    String sentAt = outbound.str("sentAt");
                    if (outbound.get("simulatorAnswered") == null && sentAt != null
                            && java.time.Instant.parse(sentAt).plusSeconds(10).isBefore(java.time.Instant.now())) {
                        answer(outbound.str("id"));
                    }
                }
            } catch (RuntimeException e) {
                LOG.error("simulator sweep failed", e);
            }
        }, 10, 5, TimeUnit.SECONDS);
        LOG.warn("external system simulator is ON. Turn it off outside development.");
    }

    public void stop() {
        timer.shutdownNow();
    }

    /** The HTTP face of the simulated systems. @param path the part after /sim */
    public Rec handle(String method, String path, Rec body) {
        String[] p = path.replaceAll("^/+", "").split("/");
        String route = method + " " + p[0] + (p.length > 1 ? "/" + p[1] : "");
        if (route.equals("POST sanctions/screen")) {
            return screen(body);
        }
        if (method.equals("GET") && p[0].equals("accounts") && p.length == 2) {
            boolean iban = p[1].matches("[A-Z]{2}[0-9]{2}.*");
            // the first digits of the account number say what the account system answers; in a German IBAN that is the last ten characters
            String number = iban && p[1].startsWith("DE") && p[1].length() == 22 ? p[1].substring(12) : p[1];
            String status = number.startsWith("000") ? "UNKNOWN" : number.startsWith("999") ? "CLOSED" : number.startsWith("666") ? "BLOCKED" : "ACTIVE";
            return Rec.of("account", p[1], "status", status, "currency", iban ? "EUR" : "ZAR", "type", "CURRENT");
        }
        if (route.equals("POST fraud/check")) {
            String name = upper(body.str("creditorName"));
            if (name.contains("SLOWCHECK")) {
                String txnId = body.str("reference");
                timer.schedule(() -> drop("SIMFRAUD-" + txnId, "json", Json.pretty(Rec.of("messageType", "fraud.result",
                        "transactionId", txnId, "status", "CLEAR", "score", 12))), 2, TimeUnit.SECONDS);
                return Rec.of("status", "PENDING", "reference", body.str("reference"));
            }
            return name.contains("FRAUDCHECK") ? Rec.of("status", "REVIEW", "score", 87, "reason", "Unusual beneficiary for this debtor")
                    : Rec.of("status", "CLEAR", "score", 5);
        }
        if (route.equals("POST compliance/check")) {
            boolean review = upper(body.str("remittance")).contains("COMPLIANCE");
            boolean document = !"ZAR".equals(body.str("currency")) && !"EUR".equals(body.str("currency")) && body.get("amount") != null
                    && Ops.num(body.get("amount")).compareTo(new BigDecimal("50000")) >= 0;
            return Rec.of("status", review ? "REVIEW" : "PASS", "rule", review ? "MANUAL_REVIEW" : null, "documentRequired", document);
        }
        if (method.equals("GET") && p[0].equals("fx") && p.length == 3) {
            String rate = "ZAR".equals(p[1]) ? RATES.get(p[2]) : null;
            if (rate == null) {
                throw new IllegalArgumentException("no rate is quoted for " + p[1] + "/" + p[2]);
            }
            return Rec.of("from", p[1], "to", p[2], "rate", new BigDecimal(rate), "quotedAt", Platform.now());
        }
        if (route.equals("POST liquidity/check")) {
            int asked = liquidityRequests.merge(String.valueOf(body.str("reference")), 1, Integer::sum);
            boolean shortOfFunds = upper(body.str("creditorName")).contains("NOLIQUIDITY") && asked <= 2;
            return Rec.of("status", shortOfFunds ? "INSUFFICIENT" : "OK", "requests", asked);
        }
        if (route.equals("POST postings/reverse")) {
            return postings.computeIfAbsent(required(body, "idempotencyKey"), k -> Rec.of("status", "REVERSED",
                    "reversalId", "SIMREV-" + postingNumber.incrementAndGet(), "postingId", body.str("postingId"))).copy();
        }
        if (route.equals("POST postings")) {
            String debit = String.valueOf(body.str("debitAccount"));
            if ((debit.startsWith("DE") && debit.length() == 22 ? debit.substring(12) : debit).startsWith("555")) {
                return Rec.of("status", "REFUSED", "reason", "INSUFFICIENT_FUNDS", "debitAccount", debit);
            }
            return postings.computeIfAbsent(required(body, "idempotencyKey"), k -> Rec.of("status", "POSTED",
                    "postingId", "SIMPST-" + postingNumber.incrementAndGet(), "debitAccount", body.str("debitAccount"),
                    "creditAccount", body.str("creditAccount"), "amount", body.get("amount"), "currency", body.str("currency"))).copy();
        }
        if (route.equals("POST nostro/statement")) {
            return Rec.of("entries", nostroStatement(required(body, "currency")));
        }
        if (route.equals("POST clearing/return")) {
            returnFunds(required(body, "transactionId"), body.str("reasonCode"), body.str("reasonText"));
            return Rec.of("ok", true);
        }
        throw new IllegalArgumentException("the simulator has nothing at " + method + " " + path);
    }

    /** Number of postings and reversals made, for tests of idempotency. */
    public int postingCount() {
        return postings.size();
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT);
    }

    private static String required(Rec body, String key) {
        if (body.str(key) == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return body.str(key);
    }

    public static Rec screen(Rec request) {
        String name = upper(request.str("name"));
        String status = name.contains("BLOCKED") ? "HIT" : name.contains("REVIEWME") ? "POSSIBLE" : "CLEAR";
        return Rec.of("status", status, "list", status.equals("CLEAR") ? null : "SIMULATED-LIST", "reference", request.str("reference"));
    }

    private void answer(String outboundId) {
        Rec outbound = platform.store.get(DocStore.OUTBOUND, outboundId);
        Rec channel = platform.deployments.registry().config(outbound.str("channel"));
        String mode = channel == null ? null : channel.str("simulator");
        // answered once, whether the announcement or the sweep gets here first
        if (mode == null || outbound.get("simulatorAnswered") != null
                || !platform.store.updateIf(DocStore.OUTBOUND, outboundId, Rec.of("status", outbound.str("status")), Rec.of("simulatorAnswered", true))) {
            return;
        }
        if ("clearing".equals(mode)) {
            statusReport(outbound);
        } else if ("clearing-cancellation".equals(mode)) {
            resolution(outbound);
        }
    }

    private void statusReport(Rec outbound) {
        String outboundId = outbound.str("id");
        Rec doc = new Rec();
        doc.set("Document.@xmlns", IsoXml.namespaceOf("pacs.002.001.10"));
        doc.set("Document.FIToFIPmtStsRpt.GrpHdr.MsgId", "SIMACK-" + outboundId);
        doc.set("Document.FIToFIPmtStsRpt.GrpHdr.CreDtTm", Platform.now().substring(0, 19));
        doc.set("Document.FIToFIPmtStsRpt.OrgnlGrpInfAndSts.OrgnlMsgId", outboundId);
        doc.set("Document.FIToFIPmtStsRpt.OrgnlGrpInfAndSts.OrgnlMsgNmId", outbound.str("messageType"));
        for (Object id : Ops.list(outbound.get("transactionIds"))) {
            Rec txn = platform.store.get(DocStore.TXN, Ops.str(id));
            boolean reject = upper(Ops.str(txn.at("creditor.name"))).contains("REJECTME")
                    || ("DD".equals(txn.str("paymentType")) && upper(Ops.str(txn.at("debtor.name"))).contains("REJECTME"));
            Rec status = new Rec();
            status.set("OrgnlEndToEndId", txn.str("endToEndId"));
            status.set("OrgnlTxId", txn.str("id"));
            status.set("TxSts", reject ? "RJCT" : "ACSC");
            if (reject) {
                status.set("StsRsnInf.Rsn.Cd", "AC04");
                status.set("StsRsnInf.AddtlInf", "Closed account number");
            }
            doc.list("Document.FIToFIPmtStsRpt.TxInfAndSts").add(status);
        }
        drop("SIMACK-" + outboundId, "xml", IsoXml.write(doc));
    }

    private void resolution(Rec outbound) {
        String outboundId = outbound.str("id");
        Rec doc = new Rec();
        doc.set("Document.@xmlns", IsoXml.namespaceOf("camt.029.001.09"));
        doc.set("Document.RsltnOfInvstgtn.Assgnmt.Id", "SIMRES-" + outboundId);
        doc.set("Document.RsltnOfInvstgtn.Assgnmt.CreDtTm", Platform.now().substring(0, 19));
        doc.set("Document.RsltnOfInvstgtn.RslvdCase.Id", outboundId);
        boolean any = false;
        for (Object id : Ops.list(outbound.get("transactionIds"))) {
            Rec txn = platform.store.get(DocStore.TXN, Ops.str(id));
            boolean refuse = upper(Ops.str(txn.at("creditor.name"))).contains("NOCANCEL");
            if (!any) {
                doc.set("Document.RsltnOfInvstgtn.Sts.Conf", refuse ? "RJCR" : "CNCL");
                any = true;
            }
            Rec status = new Rec();
            status.set("OrgnlEndToEndId", txn.str("endToEndId"));
            status.set("OrgnlTxId", txn.str("id"));
            status.set("TxCxlSts", refuse ? "RJCR" : "ACCR");
            if (refuse) {
                status.set("CxlStsRsnInf.Rsn.Cd", "CUST");
                status.set("CxlStsRsnInf.AddtlInf", "Beneficiary refuses to return the funds");
            }
            doc.list("Document.RsltnOfInvstgtn.CxlDtls.TxInfAndSts").add(status);
        }
        drop("SIMRES-" + outboundId, "xml", IsoXml.write(doc));
    }

    /**
     * The correspondent sends a camt.053 for our account in the currency: one debit for each SWIFT payment
     * in that currency that is sent and not yet reconciled, and one charge that matches nothing.
     */
    public int nostroStatement(String currency) {
        BigDecimal opening = new BigDecimal("5000000.00");
        BigDecimal balance = opening;
        Rec doc = new Rec();
        String id = "SIMSTM-" + currency + "-" + System.currentTimeMillis();
        doc.set("Document.@xmlns", IsoXml.namespaceOf("camt.053.001.08"));
        doc.set("Document.BkToCstmrStmt.GrpHdr.MsgId", id);
        doc.set("Document.BkToCstmrStmt.GrpHdr.CreDtTm", Platform.now().substring(0, 19));
        Rec stmt = new Rec();
        stmt.set("Id", id);
        stmt.set("Acct.Id.Othr.Id", "NOSTRO-" + currency);
        stmt.set("Acct.Ccy", currency);
        java.util.List<Object> entries = new java.util.ArrayList<>();
        for (Rec txn : platform.store.find(DocStore.TXN, Rec.of("status", "SENT", "currency", currency, "route.scheme", "SWIFT"), "id", false, 500)) {
            if (txn.get("reconciliation") != null) {
                continue;
            }
            BigDecimal amount = Ops.num(txn.get("amount"));
            balance = balance.subtract(amount);
            entries.add(entry(currency, amount, "DBIT", txn.str("id"), "Payment " + txn.str("endToEndId")));
        }
        BigDecimal charge = new BigDecimal("12.50");
        balance = balance.subtract(charge);
        entries.add(entry(currency, charge, "DBIT", "CHG-" + System.currentTimeMillis(), "Account maintenance charge"));
        java.util.List<Object> balances = new java.util.ArrayList<>();
        balances.add(balance("OPBD", currency, opening));
        balances.add(balance("CLBD", currency, balance));
        stmt.put("Bal", balances);
        stmt.put("Ntry", entries);
        doc.list("Document.BkToCstmrStmt.Stmt").add(stmt);
        drop(id, "xml", IsoXml.write(doc));
        return entries.size();
    }

    private static Rec balance(String code, String currency, BigDecimal amount) {
        Rec b = new Rec();
        b.set("Tp.CdOrPrtry.Cd", code);
        b.set("Amt.@Ccy", currency);
        b.set("Amt.#text", amount.abs().toPlainString());
        b.set("CdtDbtInd", amount.signum() < 0 ? "DBIT" : "CRDT");
        b.set("Dt.Dt", Platform.now().substring(0, 10));
        return b;
    }

    private static Rec entry(String currency, BigDecimal amount, String direction, String reference, String text) {
        Rec e = new Rec();
        e.set("Amt.@Ccy", currency);
        e.set("Amt.#text", amount.toPlainString());
        e.set("CdtDbtInd", direction);
        e.set("Sts.Cd", "BOOK");
        e.set("BookgDt.Dt", Platform.now().substring(0, 10));
        e.set("ValDt.Dt", Platform.now().substring(0, 10));
        e.set("NtryDtls.TxDtls.Refs.InstrId", reference);
        e.set("AddtlNtryInf", text);
        return e;
    }

    /** The receiving side sends back a payment it had accepted. */
    public void returnFunds(String txnId, String reasonCode, String reasonText) {
        Rec txn = platform.store.get(DocStore.TXN, txnId);
        if (txn == null) {
            throw new IllegalArgumentException("no transaction " + txnId);
        }
        String returnId = "SIMRTN-" + txnId + "-" + System.currentTimeMillis();
        Rec doc = new Rec();
        doc.set("Document.@xmlns", IsoXml.namespaceOf("pacs.004.001.09"));
        doc.set("Document.PmtRtr.GrpHdr.MsgId", returnId);
        doc.set("Document.PmtRtr.GrpHdr.CreDtTm", Platform.now().substring(0, 19));
        doc.set("Document.PmtRtr.GrpHdr.NbOfTxs", "1");
        Rec info = new Rec();
        info.set("RtrId", returnId);
        info.set("OrgnlEndToEndId", txn.str("endToEndId"));
        info.set("OrgnlTxId", txn.str("id"));
        info.set("RtrdIntrBkSttlmAmt.@Ccy", txn.str("currency"));
        info.set("RtrdIntrBkSttlmAmt.#text", Ops.str(txn.get("amount")));
        info.set("RtrRsnInf.Rsn.Cd", reasonCode == null ? "AC04" : reasonCode);
        info.set("RtrRsnInf.AddtlInf", reasonText == null ? "Closed account number" : reasonText);
        doc.list("Document.PmtRtr.TxInf").add(info);
        drop(returnId, "xml", IsoXml.write(doc));
    }

    private void drop(String name, String extension, String content) {
        try {
            Path inbound = platform.dataDir.resolve("inbound");
            Files.createDirectories(inbound);
            Path tmp = inbound.resolve(name + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, inbound.resolve(name + "." + extension), StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            LOG.error("simulator could not write {}", name, e);
        }
    }
}
