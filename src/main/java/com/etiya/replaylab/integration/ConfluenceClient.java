package com.etiya.replaylab.integration;

import com.etiya.replaylab.model.ConfluenceConnectivityResult;
import com.etiya.replaylab.model.ConfluencePageDocument;
import com.etiya.replaylab.model.ConfluenceSearchRequest;
import com.etiya.replaylab.model.ConfluenceSearchResponse;

public interface ConfluenceClient {
    ConfluenceConnectivityResult connectivity();

    ConfluenceSearchResponse search(ConfluenceSearchRequest request);

    ConfluencePageDocument getPage(String pageId);
}
