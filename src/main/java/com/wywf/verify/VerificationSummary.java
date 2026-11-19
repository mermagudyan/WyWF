package com.wywf.verify;

import java.util.List;

public record VerificationSummary(Status status, int spawnX, int spawnY, int spawnZ,
                                  List<String> evidence, String note) {
    public enum Status { BOOTING, VERIFYING, PASS, FAIL, PARTIAL, ERROR, CANCELLED, JOINED }
    public VerificationSummary { evidence = List.copyOf(evidence); }
    public boolean terminal() { return status != Status.BOOTING && status != Status.VERIFYING; }
}
