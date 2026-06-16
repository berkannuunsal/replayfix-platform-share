package com.etiya.replayfix.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardRedirectController {

    @GetMapping({"/replayfix", "/replayfix/"})
    public String forwardToDashboard() {
        return "forward:/replayfix/index.html";
    }
}
