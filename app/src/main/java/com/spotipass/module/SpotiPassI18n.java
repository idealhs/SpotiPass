package com.spotipass.module;

import java.util.Locale;

final class SpotiPassI18n {

    private SpotiPassI18n() {}

    static boolean isSimplifiedChinese() {
        Locale locale = Locale.getDefault();
        if (locale == null) return false;
        if (!"zh".equalsIgnoreCase(locale.getLanguage())) return false;

        String country = locale.getCountry();
        if (country == null || country.isEmpty()) return true;

        String normalizedCountry = country.toUpperCase(Locale.US);
        return !"TW".equals(normalizedCountry)
                && !"HK".equals(normalizedCountry)
                && !"MO".equals(normalizedCountry);
    }

    static String text(String zhHans, String english) {
        return isSimplifiedChinese() ? zhHans : english;
    }
}
