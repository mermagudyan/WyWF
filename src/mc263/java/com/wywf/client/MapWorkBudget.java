package com.wywf.client;

import dev.diylib.client.ClientWorkBudget;

final class MapWorkBudget {
    private final ClientWorkBudget budget = new ClientWorkBudget(3_000_000L, 8);

    boolean claim() { return budget.claim(); }
}
