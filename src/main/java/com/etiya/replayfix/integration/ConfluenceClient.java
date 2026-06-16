package com.etiya.replayfix.integration;

import com.etiya.replayfix.model.ConfluenceConnectivityResult;
import com.etiya.replayfix.model.ConfluencePageDocument;
import com.etiya.replayfix.model.ConfluenceSearchRequest;
import com.etiya.replayfix.model.ConfluenceSearchResponse;

public interface ConfluenceClient {
    ConfluenceConnectivityResult connectivity();

    ConfluenceSearchResponse search(ConfluenceSearchRequest request);

    ConfluencePageDocument getPage(String pageId);
}
