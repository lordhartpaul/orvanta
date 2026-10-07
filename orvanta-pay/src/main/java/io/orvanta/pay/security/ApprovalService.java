package io.orvanta.pay.security;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Maker-checker (four eyes). A maker requests a change; nothing happens until a different user
 * holding the approval permission of that change type approves it. Each change type registers
 * the action that runs on approval. The request, the decision and the result stay on record.
 */
public final class ApprovalService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    public static final String FAILED = "FAILED";

    private record Type(String approvePermission, Function<Rec, Rec> action) {
    }

    private final Platform platform;
    private final Map<String, Type> types = new HashMap<>();

    public ApprovalService(Platform platform) {
        this.platform = platform;
    }

    /** @param action receives the approval document and returns a result record stored with it */
    public void register(String type, String approvePermission, Function<Rec, Rec> action) {
        types.put(type, new Type(approvePermission, action));
    }

    public String approvePermission(String type) {
        Type t = types.get(type);
        return t == null ? null : t.approvePermission();
    }

    public Rec request(String type, String summary, Rec payload, String maker, String comment) {
        if (!types.containsKey(type)) {
            throw new IllegalArgumentException("unknown approval type " + type);
        }
        Rec approval = Rec.of("id", platform.newId("ORVAPR"), "type", type, "summary", summary, "payload", payload,
                "status", PENDING, "maker", maker, "makerComment", comment, "requestedAt", Platform.now());
        platform.store.insert(DocStore.APPROVAL, approval);
        platform.event(approval.str("id"), "REQUESTED", summary, null, maker);
        return approval;
    }

    /**
     * @throws SecurityException when the checker is the maker or lacks the permission
     * @throws IllegalStateException when the request is no longer pending
     */
    public Rec decide(String id, boolean approve, AuthService.Principal checker, String comment) {
        Rec approval = platform.store.get(DocStore.APPROVAL, id);
        if (approval == null) {
            throw new IllegalArgumentException("no approval request " + id);
        }
        Type type = types.get(approval.str("type"));
        if (!checker.can(type.approvePermission())) {
            throw new SecurityException("permission " + type.approvePermission() + " is required to decide this request");
        }
        if (checker.username().equals(approval.str("maker"))) {
            throw new SecurityException("a request cannot be decided by the user who made it");
        }
        // claim first: two checkers deciding at the same moment must not both run the action
        if (!platform.store.updateIf(DocStore.APPROVAL, id, Rec.of("status", PENDING),
                Rec.of("status", approve ? "APPLYING" : DECLINED, "checker", checker.username(),
                        "checkerComment", comment == null ? "" : comment, "decidedAt", Platform.now()))) {
            throw new IllegalStateException("request " + id + " is already " + approval.str("status"));
        }
        if (!approve) {
            platform.event(id, DECLINED, comment, null, checker.username());
            return platform.store.get(DocStore.APPROVAL, id);
        }
        try {
            approval.put("checker", checker.username());
            Rec result = type.action().apply(approval);
            platform.store.updateIf(DocStore.APPROVAL, id, new Rec(), Rec.of("status", APPROVED, "result", result == null ? new Rec() : result));
            platform.event(id, APPROVED, comment, result, checker.username());
        } catch (RuntimeException e) {
            platform.store.updateIf(DocStore.APPROVAL, id, new Rec(), Rec.of("status", FAILED, "failure", String.valueOf(e.getMessage())));
            platform.event(id, FAILED, e.getMessage(), null, checker.username());
        }
        return platform.store.get(DocStore.APPROVAL, id);
    }
}
