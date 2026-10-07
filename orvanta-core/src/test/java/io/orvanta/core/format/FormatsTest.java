package io.orvanta.core.format;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Fn;
import io.orvanta.core.expr.Ops;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatsTest {

    private static final String PACS002 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
              <ns2:FIToFIPmtStsRpt>
                <ns2:GrpHdr><ns2:MsgId>ACK-1</ns2:MsgId></ns2:GrpHdr>
                <ns2:TxInfAndSts><ns2:OrgnlTxId>T1</ns2:OrgnlTxId><ns2:TxSts>ACSC</ns2:TxSts></ns2:TxInfAndSts>
                <ns2:TxInfAndSts><ns2:OrgnlTxId>T2</ns2:OrgnlTxId><ns2:TxSts>RJCT</ns2:TxSts></ns2:TxInfAndSts>
              </ns2:FIToFIPmtStsRpt>
            </ns2:Document>
            """;

    @Test
    void anyIsoMessageIsReadWithoutAMessageSpecificClass() {
        List<Messages.Parsed> parsed = Messages.parse(PACS002);
        assertEquals(1, parsed.size());
        assertEquals("pacs.002.001.10", parsed.get(0).messageType());
        Rec tree = parsed.get(0).tree();
        assertEquals("ACK-1", tree.str("Document.FIToFIPmtStsRpt.GrpHdr.MsgId"));
        assertEquals(2, Ops.list(tree.at("Document.FIToFIPmtStsRpt.TxInfAndSts")).size());
        assertTrue(Messages.typeMatches("pacs.002", parsed.get(0).messageType()));
    }

    @Test
    void isoTreeSurvivesAWriteAndReadCycleIncludingAttributes() {
        Rec tree = new Rec();
        tree.set("Document.@xmlns", IsoXml.namespaceOf("pacs.008.001.08"));
        tree.set("Document.Amt.@Ccy", "ZAR");
        tree.set("Document.Amt.#text", "10.50");
        tree.set("Document.Nm", "A & B <Ltd>");
        String xml = IsoXml.write(tree);
        Rec back = IsoXml.parse(xml);
        assertEquals("pacs.008.001.08", IsoXml.messageType(back));
        assertEquals("ZAR", back.str("Document.Amt.@Ccy"));
        assertEquals(new BigDecimal("10.50"), back.num("Document.Amt"));
        assertEquals("A & B <Ltd>", back.str("Document.Nm"));
    }

    @Test
    void externalEntitiesAreRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]><Document>&x;</Document>";
        assertThrows(IllegalArgumentException.class, () -> IsoXml.parse(xxe));
    }

    @Test
    void anyMtMessageIsReadAtBlockAndTagLevel() {
        String mt = "{1:F01BANKBEBBAXXX0000000000}{2:I103BANKDEFFXXXXN}{3:{108:REF1}{121:abc}}{4:\r\n:20:TXREF\r\n:23B:CRED\r\n"
                + ":32A:261005EUR1234,56\r\n:59:/DE89370400440532013000\r\nJOHN DOE\r\nMAIN STREET 1\r\n:71A:SHA\r\n-}{5:{CHK:123456789ABC}}";
        Rec msg = SwiftMt.parse(mt);
        assertEquals("103", msg.str("type"));
        assertEquals("BANKBEBB", msg.str("sender"));
        assertEquals("BANKDEFF", msg.str("receiver"));
        assertEquals("abc", msg.str("b3.121"));
        assertEquals("TXREF", Fn.mtField(msg, "20"));
        assertEquals("EUR", Fn.mtCcy(Fn.mtField(msg, "32A")));
        assertEquals(new BigDecimal("1234.56"), Fn.mtAmt(Fn.mtField(msg, "32A")));
        assertEquals("2026-10-05", Fn.mtDate(Fn.mtField(msg, "32A")));
        assertEquals("DE89370400440532013000", Fn.mtAccount(Fn.mtField(msg, "59")));
        assertEquals("JOHN DOE", Fn.mtName(Fn.mtField(msg, "59")));

        Rec again = SwiftMt.parse(SwiftMt.write(msg));
        assertEquals(msg.get("fields"), again.get("fields"));
        assertEquals("BANKDEFF", again.str("receiver"));
    }

    @Test
    void outputHeaderAndSeveralMessagesInOneFile() {
        String out = "{1:F01BANKDEFFAXXX0000000000}{2:O9401200261005BANKBEBBAXXX22221234562610051201N}{4:\r\n:20:STMT\r\n-}";
        Rec msg = SwiftMt.parse(out);
        assertEquals("940", msg.str("type"));
        assertEquals("BANKBEBB", msg.str("sender"));
        assertEquals("BANKDEFF", msg.str("receiver"));
        assertEquals(2, Messages.parse(out + "\r\n" + out).size());
        assertEquals("MT940", Messages.parse(out).get(0).messageType());
    }

    @Test
    void jsonMessagesAreRecognisedAndTypedByTheirMessageTypeField() {
        List<Messages.Parsed> typed = Messages.parse("{\"messageType\": \"payment.request\", \"amount\": 12.50}");
        assertEquals(Messages.JSON, typed.get(0).format());
        assertEquals("payment.request", typed.get(0).messageType());
        assertEquals(new BigDecimal("12.50"), typed.get(0).tree().num("amount"));
        assertEquals("json", Messages.parse("[{\"a\": 1}]").get(0).messageType());
        assertTrue(Messages.write(Messages.JSON, Rec.of("a", 1)).contains("\"a\" : 1"));
        // a SWIFT message also starts with a brace and must not be taken for JSON
        assertEquals(Messages.SWIFT_MT, Messages.detectFormat("{1:F01BANKBEBBAXXX0000000000}{2:I103BANKDEFFXXXXN}{4:\r\n:20:X\r\n-}"));
    }

    @Test
    void sepaCharacterSetAndZoneTimes() {
        assertEquals(true, Fn.isSepaText("Van Dijk Logistiek BV, Invoice 12/2026 (part 1)"));
        assertEquals(false, Fn.isSepaText("M\u00fcller & S\u00f8n"));
        assertEquals("Muller . Son", Fn.toSepaText("M\u00fcller & S\u00f8n"));
        Object zoned = io.orvanta.core.expr.Time.with(java.time.Instant.parse("2026-10-04T22:30:00Z"),
                () -> Fn.todayIn("Europe/Berlin") + " " + Fn.timeIn("Europe/Berlin") + " " + Fn.today());
        assertEquals("2026-10-05 00:30 2026-10-04", zoned);
        assertEquals("2026-12-28T06:00:00Z", Fn.instantOf("2026-12-28", "07:00", "Europe/Berlin"));
    }

    @Test
    void financialIdentifierChecks() {
        assertEquals(true, Fn.isIban("DE89370400440532013000"));
        assertEquals(false, Fn.isIban("DE89370400440532013001"));
        assertEquals(true, Fn.isBic("DEUTDEFF"));
        assertEquals(false, Fn.isBic("DEUT"));
        assertEquals("1234,5", Fn.mtAmount(new BigDecimal("1234.50")));
        assertEquals("1000,", Fn.mtAmount(new BigDecimal("1000")));
    }
}
