package dev.forge.bg3;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

/** Translates display text; the rule engine and saved games use canonical English values. */
@Component("words")
public class GameText {
    private static final Locale POLISH = Locale.forLanguageTag("pl");

    static String polish(String source) {
        var bundle = ResourceBundle.getBundle("i18n.game", POLISH);
        return bundle.containsKey(source) ? bundle.getString(source) : source;
    }

    public static String text(Object value) {
        if (value == null) return "";
        String source = value.toString();
        return "pl".equals(LocaleContextHolder.getLocale().getLanguage()) ? polish(source) : source;
    }

    static String format(String pattern, Object... arguments) {
        return new MessageFormat(text(pattern), LocaleContextHolder.getLocale()).format(arguments);
    }

    public String list(Collection<String> values) {
        return values.stream().map(GameText::text).collect(Collectors.joining(", "));
    }

    public String classes(Hero hero) {
        if (hero.otherClasses.isEmpty()) return text(hero.heroClass);
        return hero.classLevels().stream().map(c -> text(c.heroClass()) + " " + c.level())
                .collect(Collectors.joining(" / "));
    }

    public String partyName(PartyStore.Party party, String basePath) {
        if ("/demo/".equals(basePath) && "Aelar’s demo party".equals(party.name)) return text(party.name);
        return party.name;
    }

    public String appearance(AvatarArt.Portrait art) {
        return text(art.race()) + " " + text(art.career())
                + (art.career().equals(art.specialization()) ? "" : " · " + text(art.specialization()));
    }
}
