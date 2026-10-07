package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.format.FlatFile;
import io.orvanta.core.format.Messages;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Format model kind: fixed-width and delimited records read into a record and written from one. */
class FlatFileTest {

    static String padRight(String s, int n) {
        return s.length() >= n ? s.substring(0, n) : s + " ".repeat(n - s.length());
    }

    /** A file of the shipped sample layout formats.PaymentsFixed. */
    static String sampleFile(String fileRef, String... payments) {
        StringBuilder sb = new StringBuilder();
        sb.append("H").append(padRight(fileRef, 16)).append("20261006").append(padRight("Karoo Mining Supplies", 35)).append(padRight("4051122334", 20)).append("\r\n");
        for (String p : payments) {
            String[] f = p.split("\\|");
            sb.append("D").append(padRight(f[0], 16)).append(String.format("%012d", new BigDecimal(f[1]).movePointRight(2).longValueExact())).append(f[2])
                    .append(padRight(f[3], 35)).append(padRight(f[4], 20)).append(padRight(f[5], 11)).append(padRight(f.length > 6 ? f[6] : "", 40)).append("\r\n");
        }
        return sb.toString();
    }

    @Test
    void aFixedWidthFileIsReadByItsModelAndWrittenBackTheSame() throws Exception {
        Rec spec = ModelSource.parse("f.yaml", Files.readString(Path.of("..", "workspace", "formats", "PaymentsFixed.yaml"))).def();
        FlatFile format = FlatFile.compile(spec);
        String file = sampleFile("FLAT-20261006-1", "SAL-1|18250.00|ZAR|Thandiwe Mokoena|62011223344|FIRNZAJJ|Salary October", "SAL-2|99.5|ZAR|Pieter van der Merwe|62055667788|ABSAZAJJ");
        Rec tree = format.parse(file);
        assertEquals("formats.PaymentsFixed", tree.str("format"));
        assertEquals("FLAT-20261006-1", tree.at("header.fileRef"));
        assertEquals("Karoo Mining Supplies", tree.at("header.debtorName"), "trailing spaces are trimmed");
        List<?> payments = Ops.list(tree.get("payment"));
        assertEquals(2, payments.size(), "a repeating record kind is a list under its name");
        Rec first = (Rec) payments.get(0);
        assertEquals(new BigDecimal("18250.00"), first.get("amount"), "digits with two implied decimals");
        assertEquals("Salary October", first.str("remittance"));
        assertEquals(null, ((Rec) payments.get(1)).get("remittance"), "an empty field is absent");
        assertEquals(3, Ops.list(tree.get("records")).size());
        // written back: the same lines, amounts and numbers zero-filled on the left, text space-filled on the right
        assertEquals(file, format.write(tree));
        // the same through the entry point a channel uses
        Rec channel = Rec.of("name", "channels.X", "format", "flat", "formatSpec", "formats.PaymentsFixed");
        Messages.Parsed parsed = Messages.parse(file, channel, name -> spec).get(0);
        assertEquals("flat", parsed.format());
        assertEquals("formats.PaymentsFixed", parsed.messageType());
        assertEquals(file, Messages.write("flat", parsed.tree(), spec));
        // a line the model does not know, and a value that is not digits where digits are expected
        assertTrue(assertThrows(IllegalArgumentException.class, () -> format.parse(file + "X something\r\n")).getMessage().contains("line 4"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> format.parse(file.replace("000001825000", "0000018250.0"))).getMessage().contains("not digits"));
        // a flat file cannot be told from its text alone
        assertThrows(IllegalArgumentException.class, () -> Messages.parse(file));
    }

    @Test
    void aDelimitedFileIsReadAndWrittenWithQuotes() {
        Rec spec = ModelSource.parse("d.yaml", """
                kind: Format
                name: formats.Csv
                type: delimited
                delimiter: ","
                quote: '"'
                recordType: {index: 0}
                records:
                  - type: HDR
                    name: header
                    fields: [{name: ref, index: 1}]
                  - type: PAY
                    name: payment
                    repeats: true
                    fields: [{name: reference, index: 1}, {name: amount, index: 2, kind: amount, decimals: 2}, {name: name, index: 3}]
                """).def();
        FlatFile format = FlatFile.compile(spec);
        Rec tree = format.parse("HDR,F-1\nPAY,P-1,12345,\"Mokoena, Thandiwe\"\nPAY,P-2,50,Plain Name\n");
        assertEquals("F-1", tree.at("header.ref"));
        assertEquals("Mokoena, Thandiwe", ((Rec) Ops.list(tree.get("payment")).get(0)).str("name"));
        assertEquals(new BigDecimal("0.50"), ((Rec) Ops.list(tree.get("payment")).get(1)).get("amount"));
        assertEquals("HDR,F-1\r\nPAY,P-1,12345,\"Mokoena, Thandiwe\"\r\nPAY,P-2,50,Plain Name\r\n", format.write(tree));
    }

    @Test
    void aFormatWithAMistakeIsAProblemOfTheModelAndAChannelNamesItsFormat() {
        Forge.Build bad = Forge.build(List.of(ModelSource.parse("f.yaml", "kind: Format\nname: formats.Bad\ntype: fixed\nrecordType: {start: 1, length: 1}\nrecords:\n  - {type: A, name: a, fields: [{name: x, start: 0, length: 3}]}\n")));
        assertFalse(bad.ok());
        assertTrue(bad.problems().get(0).message().contains("start and length"), bad.problems().toString());
        Forge.Build noSpec = Forge.build(List.of(ModelSource.parse("c.yaml", "kind: Channel\nname: channels.Flat\ndirection: inbound\npurpose: instruction\nformat: flat\nmessageTypes: [x]\nmapping: m.M\n"),
                ModelSource.parse("m.yaml", "kind: Mapping\nname: m.M\nrules: [{set: a, value: src.b}]\n")));
        assertFalse(noSpec.ok());
        assertTrue(noSpec.problems().stream().anyMatch(p -> p.message().contains("formatSpec")), noSpec.problems().toString());
    }
}
