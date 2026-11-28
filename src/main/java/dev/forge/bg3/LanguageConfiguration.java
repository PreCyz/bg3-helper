package dev.forge.bg3;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.*;

@Configuration
class LanguageConfiguration implements WebMvcConfigurer {
    @Bean
    LocaleResolver localeResolver() {
        var resolver = new CookieLocaleResolver("forge-language");
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setCookiePath("/");
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookieSameSite("Lax");
        resolver.setCookieHttpOnly(true);
        resolver.setRejectInvalidCookies(false);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        var languages = new LocaleChangeInterceptor() {
            @Override
            protected Locale parseLocaleValue(String value) {
                return switch (value) {
                    case "en" -> Locale.ENGLISH;
                    case "pl" -> Locale.forLanguageTag("pl");
                    default -> throw new IllegalArgumentException("Unsupported language");
                };
            }
        };
        languages.setParamName("lang");
        languages.setIgnoreInvalidLocale(true);
        registry.addInterceptor(languages).excludePathPatterns("/art/**", "/items/**", "/webjars/**");
    }
}

@ControllerAdvice(assignableTypes = {StartController.class, ForgeController.class})
class HeaderAttributes {
    record Page(String path, Map<String, String[]> parameters) {}

    private final String buildVersion;

    HeaderAttributes(Optional<GitProperties> git) {
        buildVersion = git.map(info -> "v" + info.get("build.version") + "-" + info.get("commit.id.abbrev"))
                .orElse("");
    }

    @ModelAttribute("buildVersion")
    String buildVersion() {
        return buildVersion;
    }

    @ModelAttribute("languagePage")
    Page currentPage(HttpServletRequest request) {
        if (!request.getMethod().equals("GET")) return new Page(request.getContextPath() + "/", Map.of());
        var parameters = new LinkedHashMap<>(request.getParameterMap());
        parameters.remove("lang");
        return new Page(request.getRequestURI(), parameters);
    }
}
