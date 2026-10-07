package io.orvanta.forge;

import io.orvanta.core.expr.Ids;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Message id templates of outbound channels. */
class IdsTest {

    @Test
    void placeholdersAreFilledAndTheResultIsASwiftCharacterSetId() {
        ZonedDateTime at = ZonedDateTime.parse("2026-10-07T09:05:03+02:00[Africa/Johannesburg]");
        assertEquals("ORV20261007000028", Ids.render("ORV{yyyyMMdd}{seq:6}", 28, "rails.sepa.SctOutbound", at));
        assertEquals("SctOutbound-261007-090503-00000028", Ids.render("{channel}-{yyMMdd}-{HHmmss}-{seq}", 28, "channels.SctOutbound", at));
        assertEquals(28, Ids.sequenceOf("ORVOUT0000000028"));
        assertThrows(IllegalArgumentException.class, () -> Ids.render("X{nonsense here}", 1, "c", at));
        assertThrows(IllegalArgumentException.class, () -> Ids.render("{yyyyMMdd}{seq:40}", 1, "c", at), "too long");
        assertThrows(IllegalArgumentException.class, () -> Ids.render("A_B", 1, "c", at), "underscore is not in the character set");
        // the model compiler refuses a template it cannot render
        assertFalse(Forge.build(List.of(ModelSource.parse("c.yaml", "kind: Channel\nname: channels.X\ndirection: outbound\nformat: json\nmessageIdTemplate: \"{bad token}\"\nmapping: m.M\ndestination: {type: folder, path: out}\n"),
                ModelSource.parse("m.yaml", "kind: Mapping\nname: m.M\nrules: [{set: a, value: bulk.msgId}]\n"))).ok());
        assertTrue(Forge.build(List.of(ModelSource.parse("c.yaml", "kind: Channel\nname: channels.X\ndirection: outbound\nformat: json\nmessageIdTemplate: \"ORV{yyyyMMdd}{seq:6}\"\nmapping: m.M\ndestination: {type: folder, path: out}\n"),
                ModelSource.parse("m.yaml", "kind: Mapping\nname: m.M\nrules: [{set: a, value: bulk.msgId}]\n"))).ok());
    }
}
