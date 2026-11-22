package dev.forge.bg3;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class StartController {
    static final String DEMO = "demoPartyStore";
    private final PartyStore saved;
    private final Hero demoHero;

    StartController(PartyStore saved) throws IOException {
        this.saved = saved;
        var demo = saved.readDemoParty();
        this.demoHero = demo.hero(demo.active);
    }

    @GetMapping("/")
    String start(Model model) {
        model.addAttribute("basePath", "/");
        model.addAttribute("savedParty", saved.snapshot());
        model.addAttribute("demoHero", demoHero);
        return "start";
    }

    @GetMapping(value = "/", params = "mode")
    String previousSheetLink(@RequestParam String mode, RedirectAttributes attributes) {
        attributes.addAttribute("mode", mode);
        return "redirect:/sheet";
    }

    @PostMapping("/demo/start")
    String startDemo(HttpSession session) throws IOException {
        session.setAttribute(DEMO, saved.newDemo());
        return "redirect:/demo/sheet";
    }

    @PostMapping("/demo/exit")
    String exitDemo(HttpSession session) {
        session.removeAttribute(DEMO);
        return "redirect:/";
    }
}
