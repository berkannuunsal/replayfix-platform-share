package com.etiya.replayfix.model;

import java.util.List;

public record CorrelationSignals(
        List<String> traceIds,
        List<String> orderIds,
        List<String> correlationIds,
        List<String> processInstanceIds,
        List<String> businessKeys,
        List<String> requestIds
) {

    public boolean isEmpty() {
        return totalCount() == 0;
    }

    public int totalCount() {
        return traceIds.size()
                + orderIds.size()
                + correlationIds.size()
                + processInstanceIds.size()
                + businessKeys.size()
                + requestIds.size();
    }
}
