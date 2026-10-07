package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.ConnectorRefusal;
import io.orvanta.core.flow.SoapConnector;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A Connector of type soap against a small service: an answer, a fault that blames the request, a fault of the server. */
class SoapConnectorTest {

    private static final String ENVELOPE = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>%s</soap:Body></soap:Envelope>";

    @Test
    void anOperationIsCalledAndFaultsAreToldApart() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        AtomicInteger serverFaults = new AtomicInteger();
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/accounts", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            actions.add(exchange.getRequestHeaders().getFirst("SOAPAction") + " | " + exchange.getRequestHeaders().getFirst("Authorization"));
            int status = 200;
            String body;
            if (request.contains("<account>UNKNOWN</account>")) {
                status = 500;
                body = "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>No such account</faultstring></soap:Fault>";
            } else if (request.contains("<account>BUSY</account>") && serverFaults.incrementAndGet() <= 2) {
                status = 500;
                body = "<soap:Fault><faultcode>soap:Server</faultcode><faultstring>Try again later</faultstring></soap:Fault>";
            } else {
                body = "<ns:GetAccountStatusResponse xmlns:ns=\"http://bank.example/accounts\"><ns:status>ACTIVE</ns:status>"
                        + "<ns:holder><ns:name>Karoo Mining Supplies</ns:name><ns:country>ZA</ns:country></ns:holder></ns:GetAccountStatusResponse>";
            }
            byte[] reply = String.format(ENVELOPE, body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        service.start();
        try {
            SoapConnector connector = new SoapConnector("connectors.AccountService", Rec.of(
                    "url", "http://127.0.0.1:" + service.getAddress().getPort() + "/accounts", "operation", "GetAccountStatus",
                    "namespace", "http://bank.example/accounts", "action", "http://bank.example/accounts/GetAccountStatus",
                    "retries", 2, "retryDelayMs", 10, "auth", Rec.of("type", "bearer", "token", "soap-test-token")));

            Rec answer = connector.call(Rec.of("account", "4051122334", "options", Rec.of("withHolder", true)));
            assertEquals("ACTIVE", answer.str("status"));
            assertEquals("Karoo Mining Supplies", answer.at("holder.name"));
            String sent = requests.get(0).replaceAll(">\\s+<", "><");
            assertTrue(sent.contains("<Envelope xmlns=\"http://schemas.xmlsoap.org/soap/envelope/\"><Body><GetAccountStatus xmlns=\"http://bank.example/accounts\">"
                    + "<account>4051122334</account><options><withHolder>true</withHolder></options></GetAccountStatus></Body></Envelope>"), sent);
            assertEquals("\"http://bank.example/accounts/GetAccountStatus\" | Bearer soap-test-token", actions.get(0));

            // the service says the request is wrong: a refusal, asked once
            int before = requests.size();
            ConnectorRefusal refused = assertThrows(ConnectorRefusal.class, () -> connector.call(Rec.of("account", "UNKNOWN")));
            assertTrue(refused.getMessage().contains("No such account") && refused.getMessage().contains("Client"), refused.getMessage());
            assertEquals(before + 1, requests.size(), "a refusal is not tried again");

            // the service has a problem of its own: tried again, and the third attempt is answered
            before = requests.size();
            assertEquals("ACTIVE", connector.call(Rec.of("account", "BUSY")).str("status"));
            assertEquals(before + 3, requests.size());
        } finally {
            service.stop(0);
        }
    }
}
