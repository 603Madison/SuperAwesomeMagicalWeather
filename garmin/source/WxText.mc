import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;

//! Words for the screens and the glance: conditions, the one-line headline
//! and the next-hour rain line. The headline follows the phone app's
//! TodayHeadline (core/.../outlook/Headline.kt) in a single short sentence:
//! rain or snow first, then strong gusts, a frost after a mild day, a big
//! change in tomorrow's high, and otherwise "Dry for the next 12 hours".
(:glance)
module WxText {
    // rainSoon() states
    const DRY = 0;    // nothing in the next hour
    const START = 1;  // starts at the given time
    const STOP = 2;   // falling now, stops at the given time
    const ALL = 3;    // falling now and all hour

    const WET_MM = 0.1;  // per 15 minutes, as in the phone app

    const COLOR_DIM = 0xAAAAAA;
    const COLOR_RAIN = 0x55AAFF;
    const COLOR_WARNING = 0xFF5555;
    const COLOR_WATCH = 0xFFAA00;
    const COLOR_ADVISORY = 0xFFFF55;

    function round(x as Numeric) as Number {
        return (x + (x < 0 ? -0.5 : 0.5)).toNumber();
    }

    //! 52.4 -> "52°"; missing -> "--".
    function deg(x as Object?) as String {
        if (x instanceof Number || x instanceof Float || x instanceof Double) {
            return round(x as Numeric).toString() + "°";
        }
        return "--";
    }

    //! WMO weather code -> the phone app's condition names.
    function condition(code as Number) as String {
        if (code <= 0) { return "Clear"; }
        if (code == 1) { return "Mostly clear"; }
        if (code == 2) { return "Partly cloudy"; }
        if (code == 3) { return "Cloudy"; }
        if (code == 45 || code == 48) { return "Fog"; }
        if (code == 56 || code == 57 || code == 66 || code == 67) { return "Freezing rain"; }
        if (code >= 51 && code <= 55) { return "Drizzle"; }
        if (code == 61 || code == 63) { return "Rain"; }
        if (code == 65) { return "Heavy rain"; }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) { return "Snow"; }
        if (code >= 80 && code <= 82) { return "Showers"; }
        if (code >= 95) { return "Thunderstorms"; }
        return "--";
    }

    //! A short name for the 5-day rows.
    function shortCondition(code as Number) as String {
        if (code <= 1) { return "Clear"; }
        if (code == 2) { return "Partly"; }
        if (code == 3) { return "Cloudy"; }
        if (code == 45 || code == 48) { return "Fog"; }
        var k = kind(code);
        if (k == 2) { return "Snow"; }
        if (k == 3) { return "Mix"; }
        if (code >= 95) { return "Storms"; }
        if (code >= 80) { return "Showers"; }
        if (k == 1) { return "Rain"; }
        return "";
    }

    //! 0 dry, 1 rain, 2 snow, 3 mixed or freezing.
    function kind(code as Number) as Number {
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) { return 2; }
        if (code == 56 || code == 57 || code == 66 || code == 67) { return 3; }
        if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82) || code >= 95) { return 1; }
        return 0;
    }

    //! From a set of kinds seen (bits: 1 rain, 2 snow, 4 mix), as PrecipKind does.
    function noun(bits as Number) as String {
        if ((bits & 4) != 0 || (bits & 3) == 3) { return "Wintry mix"; }
        if ((bits & 2) != 0) { return "Snow"; }
        return "Rain";
    }

    function hourIsWet(code as Object?, pop as Object?) as Boolean {
        var p = (pop == null) ? -1 : WxData.num(pop);
        return (kind(WxData.num(code).toNumber()) > 0 && (p < 0 || p >= 30)) || p >= 60;
    }

    // ---- Times, in the watch's own time zone ----

    function is24() as Boolean {
        return System.getDeviceSettings().is24Hour;
    }

    //! "3:45" (or "15:45").
    function clock(t as Number) as String {
        var i = Gregorian.info(new Time.Moment(t), Time.FORMAT_SHORT);
        var h = i.hour as Number;
        if (!is24()) {
            h = h % 12;
            if (h == 0) {
                h = 12;
            }
        }
        return h.toString() + ":" + (i.min as Number).format("%02d");
    }

    //! "3 PM", "6:30 PM" (or "15:00").
    function hourLabel(t as Number) as String {
        var i = Gregorian.info(new Time.Moment(t), Time.FORMAT_SHORT);
        var h = i.hour as Number;
        var m = i.min as Number;
        if (is24()) {
            return h.format("%02d") + ":" + m.format("%02d");
        }
        var h12 = h % 12 == 0 ? 12 : h % 12;
        return (m == 0 ? h12.toString() : h12.toString() + ":" + m.format("%02d")) + (h < 12 ? " AM" : " PM");
    }

    //! "Thu" for a day that starts at t (local midnight at the place).
    function dayName(t as Number) as String {
        // Noon, so a watch a few time zones away still names the same day.
        return Gregorian.info(new Time.Moment(t + 12 * 3600), Time.FORMAT_MEDIUM).day_of_week as String;
    }

    //! "6 PM" today, "Thu 6 PM" on another day.
    function whenLabel(t as Number, now as Number) as String {
        var a = Gregorian.info(new Time.Moment(t), Time.FORMAT_SHORT);
        var b = Gregorian.info(new Time.Moment(now), Time.FORMAT_SHORT);
        if (a.day == b.day && a.month == b.month) {
            return hourLabel(t);
        }
        return (Gregorian.info(new Time.Moment(t), Time.FORMAT_MEDIUM).day_of_week as String) + " " + hourLabel(t);
    }

    // ---- The next hour, from the 15-minute forecast ----

    //! [state, time, kind bits] for the next hour, or null without data.
    //! q15 is [times, precipitation mm, snowfall cm].
    function rainSoon(q15 as Array?, now as Number) as Array? {
        if (q15 == null || !(q15[0] instanceof Array) || !(q15[1] instanceof Array)) {
            return null;
        }
        var times = q15[0] as Array;
        var mm = q15[1] as Array;
        var snow = (q15[2] instanceof Array) ? (q15[2] as Array) : null;
        var state = -1;
        var at = 0;
        var liquid = 0.0;
        var flakes = 0.0;
        for (var i = 0; i < times.size() && i < mm.size(); i++) {
            var t = times[i] as Number;
            if (t + 900 <= now || t >= now + 3600) {
                continue;
            }
            var wet = WxData.num(mm[i]) >= WET_MM;
            if (state < 0) {
                state = wet ? ALL : DRY;
            } else if (state == DRY && wet) {
                state = START;
                at = t;
            } else if (state == ALL && !wet) {
                state = STOP;
                at = t;
            }
            if (wet && (state == ALL || state == START)) {
                liquid += WxData.num(mm[i]);
                if (snow != null && i < snow.size()) {
                    flakes += WxData.num(snow[i]);
                }
            }
        }
        if (state < 0) {
            return null;
        }
        // Snowfall (cm) against liquid (mm), as the phone's 15-minute line does.
        var bits = flakes <= 0 ? 1 : (flakes >= liquid * 0.8 ? 2 : 4);
        return [state, at, bits];
    }

    //! "Rain ~3:45", "Snow until ~4:15", "Rain all hour", "Dry next hour", or null.
    function rainLine(soon as Array?) as String? {
        if (soon == null) {
            return null;
        }
        var state = soon[0] as Number;
        var n = noun(soon[2] as Number);
        if (state == START) {
            return n + " ~" + clock(soon[1] as Number);
        }
        if (state == STOP) {
            return n + " until ~" + clock(soon[1] as Number);
        }
        if (state == ALL) {
            return n + " all hour";
        }
        return "Dry next hour";
    }

    // ---- Headline ----

    //! One short sentence about what matters next. soon is rainSoon()'s result.
    function headline(wx as Array, now as Number, soon as Array?) as String {
        var metric = wx[WxData.F_METRIC] == true;
        var h = wx[WxData.F_HOURLY] as Array;
        var d = wx[WxData.F_DAILY] as Array;
        var times = h[0] as Array;
        var temps = h[1] as Array?;
        var pops = h[2] as Array?;
        var codes = h[3] as Array?;
        var gusts = h[4] as Array?;

        // Rain or snow in the next 12 hours: when it starts, or when it stops.
        var future = 0;
        var start = null;
        var stop = null;
        var ongoing = false;
        var bits = 0;
        for (var i = 0; i < times.size(); i++) {
            var t = times[i] as Number;
            if (t + 3600 <= now) {
                continue;
            }
            var code = (codes == null) ? 0 : WxData.num(codes[i]).toNumber();
            var wet = hourIsWet(code, pops == null ? null : pops[i]);
            if (future == 0) {
                ongoing = wet;
            }
            future++;
            if (wet && stop == null) {
                if (start == null) {
                    start = t;
                }
                var k = kind(code);
                bits = bits | ((k == 2) ? 2 : (k == 3 ? 4 : 1));
            } else if (!wet && start != null && stop == null) {
                stop = t;
            }
        }
        if (future == 0) {
            return "";
        }
        var rain = null;
        if (start != null) {
            var n = noun(bits);
            if (ongoing) {
                rain = (stop != null) ? n + " until about " + hourLabel(stop as Number) : n + " through the next 12 hours";
            } else {
                rain = n + " from " + hourLabel(start as Number);
            }
            // The next-hour line already says it when it's within two hours.
            var covered = soon != null && soon[0] != DRY && (start as Number) < now + 7200;
            if (!covered) {
                return rain;
            }
        }

        // Strong gusts, about 35 mph.
        if (gusts != null) {
            var limit = metric ? 56 : 35;
            var best = -1;
            var bestGust = 0;
            for (var i = 0; i < times.size() && i < gusts.size(); i++) {
                var g = WxData.num(gusts[i]);
                if ((times[i] as Number) + 3600 > now && g >= limit && g > bestGust) {
                    best = i;
                    bestGust = g;
                }
            }
            if (best >= 0) {
                return "Gusts to " + round(bestGust) + (metric ? " km/h" : " mph") + " around " + hourLabel(times[best] as Number);
            }
        }

        var highs = d[1] as Array?;
        var lows = d[2] as Array?;
        var high0 = (highs != null && highs.size() > 0) ? highs[0] : null;
        var high1 = (highs != null && highs.size() > 1) ? highs[1] : null;

        // Freezing tonight after a day above about 39 °F.
        var hour = Gregorian.info(new Time.Moment(now), Time.FORMAT_SHORT).hour as Number;
        var low = null;
        if (hour >= 12 && lows != null && lows.size() > 1) {
            low = lows[1];  // tomorrow's low is around dawn
        } else if (hour < 5 && temps != null) {
            for (var i = 0; i < times.size() && i < temps.size(); i++) {
                var t = times[i] as Number;
                if (t > now && t < now + (9 - hour) * 3600 && temps[i] != null && (low == null || WxData.num(temps[i]) < WxData.num(low))) {
                    low = temps[i];
                }
            }
        }
        if (low != null && high0 != null) {
            var lo = WxData.num(low);
            if (lo <= (metric ? 0.0 : 32.0) && WxData.num(high0) >= (metric ? 4.0 : 39.2)) {
                return (lo <= (metric ? -2.0 : 28.4) ? "A freeze" : "Frost") + " tonight, low " + deg(low);
            }
        }

        // A big change in tomorrow's high, about 11 °F.
        if (high0 != null && high1 != null) {
            var diff = WxData.num(high1) - WxData.num(high0);
            var big = metric ? 6.0 : 10.8;
            if (diff <= -big) {
                return "Much colder tomorrow, high " + deg(high1);
            }
            if (diff >= big) {
                return "Much warmer tomorrow, high " + deg(high1);
            }
        }
        return (rain != null) ? (rain as String) : "Dry for the next 12 hours";
    }

    //! Words for a failed refresh.
    function errorText(code as Number) as String {
        if (code == -104 || code == -2) {
            return "Phone not connected";
        }
        if (code == -3 || code == -300) {
            return "No answer, try later";
        }
        if (code == -402 || code == -403) {
            return "Response too large";
        }
        if (code == -400) {
            return "Bad response";
        }
        return "Update failed (" + code + ")";
    }

    // Colors fall back to white on black-and-white screens (Instinct), see
    // monkey.jungle.
    (:glance :color)
    function col(c as Number) as Number {
        return c;
    }

    (:glance :mono)
    function col(c as Number) as Number {
        return Graphics.COLOR_WHITE;
    }

    function alertColor(rank as Number) as Number {
        if (rank >= 3) {
            return col(COLOR_WARNING);
        }
        if (rank == 2) {
            return col(COLOR_WATCH);
        }
        if (rank == 1) {
            return col(COLOR_ADVISORY);
        }
        return Graphics.COLOR_WHITE;
    }
}
