package com.testgen.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PreviewController {

    /** Serves the main UI page. */
    @GetMapping("/")
    public String index() {
        return "index";
    }
}
