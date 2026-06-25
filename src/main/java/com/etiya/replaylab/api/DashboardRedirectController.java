package com.etiya.replaylab.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardRedirectController {

    @GetMapping({"/replaylab", "/replaylab/"})
    public String forwardToDashboard() {
        return "forward:/replaylab/index.html";
    }

    @GetMapping({"/replayfix", "/replayfix/"})
    public String redirectLegacyDashboard() {
        return "redirect:/replaylab";
    }
}
