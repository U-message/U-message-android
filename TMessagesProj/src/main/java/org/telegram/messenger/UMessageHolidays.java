package org.telegram.messenger;

import android.content.Context;
import android.icu.util.IslamicCalendar;

import java.util.Calendar;

/**
 * U message: which holiday decoration the chats screen shows today (see UMessageHolidayView).
 * Winter: 24 December – 1 January. Navro'z: 20–22 March. Hayit: the three days of Ramazon hayit (1–3 Shawwal)
 * and of Qurbon hayit (10–12 Dhu al-Hijjah), from the Umm al-Qura calendar, so no yearly update is needed;
 * the day announced locally can differ by one. Each one can be switched off from the admin panel; when the admin
 * leaves exactly one switched on, that one is shown right away regardless of the date. The user
 * chooses in U message settings: automatic, off, or one of them all the time.
 */
public final class UMessageHolidays {

    public static final int NONE = 0, SNOW = 1, NAVRUZ = 2, HAYIT = 3;
    /** User choice: by the calendar, never, or always one of the decorations. */
    public static final int MODE_AUTO = 0, MODE_OFF = 1, MODE_SNOW = 2, MODE_NAVRUZ = 3, MODE_HAYIT = 4;

    private static final String PREFS = "umessage_holidays", KEY_MODE = "mode";
    private static int cached = -1;
    private static long cachedAt;
    private static final long CACHE_MS = 5_000;

    private UMessageHolidays() {
    }

    public static int getMode() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_AUTO);
    }

    public static void setMode(int mode) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply();
        cached = -1;
    }

    private static boolean allowed(int holiday) {
        switch (holiday) {
            case SNOW: return UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.HOLIDAY_SNOW);
            case NAVRUZ: return UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.HOLIDAY_NAVRUZ);
            case HAYIT: return UMessageRemoteFlags.isEnabled(UMessageRemoteFlags.HOLIDAY_HAYIT);
        }
        return false;
    }

    /** The decoration to draw now; cheap to call every frame. */
    public static int current() {
        final long now = System.currentTimeMillis();
        if (cached >= 0 && now - cachedAt < CACHE_MS) {
            return cached;
        }
        cachedAt = now;
        int result;
        try {
            final int mode = getMode();
            if (mode == MODE_OFF) {
                result = NONE;
            } else if (mode != MODE_AUTO) {
                result = mode - 1;
            } else {
                // admin panel: with exactly one decoration left switched on, it is shown now, whatever the date
                final boolean snow = allowed(SNOW), navruz = allowed(NAVRUZ), hayit = allowed(HAYIT);
                final int on = (snow ? 1 : 0) + (navruz ? 1 : 0) + (hayit ? 1 : 0);
                result = on == 1 ? (snow ? SNOW : navruz ? NAVRUZ : HAYIT) : byCalendar();
            }
            if (result != NONE && !allowed(result)) {
                result = NONE;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            result = NONE;
        }
        return cached = result;
    }

    private static int byCalendar() {
        final Calendar c = Calendar.getInstance();
        final int month = c.get(Calendar.MONTH), day = c.get(Calendar.DAY_OF_MONTH);
        if (month == Calendar.DECEMBER && day >= 24 || month == Calendar.JANUARY && day == 1) {
            return SNOW;
        }
        if (month == Calendar.MARCH && day >= 20 && day <= 22) {
            return NAVRUZ;
        }
        final IslamicCalendar h = new IslamicCalendar();
        h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        h.setTimeInMillis(System.currentTimeMillis());
        final int hMonth = h.get(IslamicCalendar.MONTH), hDay = h.get(IslamicCalendar.DAY_OF_MONTH);
        if (hMonth == IslamicCalendar.SHAWWAL && hDay <= 3 || hMonth == IslamicCalendar.DHU_AL_HIJJAH && hDay >= 10 && hDay <= 12) {
            return HAYIT;
        }
        return NONE;
    }
}
