import Toybox.Application;
import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.Lang;
import Toybox.System;

//! Storage keys, settings and response parsing shared by the app, the glance
//! and the background refresh. Keep this small: on Instinct-class watches the
//! background process has 32 KB for code and data together.
(:background :glance)
module WxData {
    const WX = "wx";     // forecast, laid out by Fetcher.parse()
    const AL = "al";     // [fetched at, Array of alerts or "us" (outside the US) or "na" (unavailable)]
    const LOC = "loc";   // [lat, lon] last position from the watch
    const GEO = "geo";   // [query, lat, lon, name] the saved place, geocoded
    const ERR = "err";   // [time, response code] of the last failed refresh
    const SEEN = "seen"; // warnings already announced by the background refresh

    // Positions in the stored forecast array.
    const F_TIME = 0;    // when it was fetched (epoch seconds)
    const F_METRIC = 1;  // true: °C and km/h, false: °F and mph
    const F_CUR = 2;     // [temperature, weather code, is day, gusts]
    const F_HOURLY = 3;  // [times, temperatures, precip chance %, weather codes, gusts], 12 hours
    const F_DAILY = 4;   // [times, highs, lows, weather codes, precip chance %], 5 days
    const F_Q15 = 5;     // [times, precipitation mm, snowfall cm], 4 x 15 minutes, or null
    const F_PLACE = 6;   // name of the saved place, or null for the watch's position

    // Positions in one alert.
    const A_EVENT = 0;
    const A_ENDS = 1;
    const A_HEADLINE = 2;
    const A_RANK = 3;    // 3 warning, 2 watch, 1 advisory, 0 other

    //! A number setting, or def when it is missing.
    function prop(key as String, def as Number) as Number {
        var v = null;
        try {
            v = Properties.getValue(key);
        } catch (e) {
        }
        return (v instanceof Number) ? (v as Number) : def;
    }

    function flag(key as String, def as Boolean) as Boolean {
        var v = null;
        try {
            v = Properties.getValue(key);
        } catch (e) {
        }
        return (v instanceof Boolean) ? (v as Boolean) : def;
    }

    function text(key as String) as String {
        var v = null;
        try {
            v = Properties.getValue(key);
        } catch (e) {
        }
        return (v instanceof String) ? trim(v as String) : "";
    }

    //! Units setting: 0 follows the watch, 1 is °F/mph, 2 is °C/km/h.
    function isMetric() as Boolean {
        var u = prop("units", 0);
        if (u == 1) {
            return false;
        }
        if (u == 2) {
            return true;
        }
        return System.getDeviceSettings().temperatureUnits == System.UNIT_METRIC;
    }

    //! True when the user picked a saved place and typed one.
    function usesPlace() as Boolean {
        return prop("source", 0) == 1 && text("place").length() > 0;
    }

    //! Where to get weather for without asking the watch for a new position:
    //! [lat, lon, name or null], or null when that isn't known yet (a place
    //! name that hasn't been looked up, or no position saved).
    function storedLocation() as Array? {
        if (usesPlace()) {
            var q = text("place");
            var ll = parseLatLon(q);
            if (ll != null) {
                return [ll[0], ll[1], null];
            }
            var g = Storage.getValue(GEO);
            if (g instanceof Array && g.size() == 4 && q.equals(g[0])) {
                return [g[1], g[2], g[3]];
            }
            return null;
        }
        var l = Storage.getValue(LOC);
        if (l instanceof Array && l.size() >= 2) {
            return [l[0], l[1], null];
        }
        return null;
    }

    function trim(s as String) as String {
        var cs = s.toCharArray();
        var a = 0;
        var b = cs.size();
        while (a < b && cs[a] == ' ') {
            a++;
        }
        while (b > a && cs[b - 1] == ' ') {
            b--;
        }
        return s.substring(a, b) as String;
    }

    //! "43.66,-70.26" -> [43.66, -70.26]; anything else -> null.
    function parseLatLon(s as String) as Array? {
        var c = s.find(",");
        if (c == null) {
            return null;
        }
        var a = trim(s.substring(0, c) as String).toFloat();
        var b = trim(s.substring(c + 1, s.length()) as String).toFloat();
        if (a == null || b == null || a < -90 || a > 90 || b < -180 || b > 180) {
            return null;
        }
        return [a, b];
    }

    //! "2026-11-13T10:00:00-05:00" -> epoch seconds, or null.
    function isoToEpoch(v as Object?) as Number? {
        if (!(v instanceof String)) {
            return null;
        }
        var s = v as String;
        if (s.length() < 19) {
            return null;
        }
        var y = two(s, 0, 4);
        var mo = two(s, 5, 7);
        var d = two(s, 8, 10);
        var h = two(s, 11, 13);
        var mi = two(s, 14, 16);
        var se = two(s, 17, 19);
        if (y == null || mo == null || d == null || h == null || mi == null || se == null) {
            return null;
        }
        // Offset after the seconds (and any fraction): "Z", "+hh:mm" or "-hh:mm".
        var off = 0;
        var tail = s.substring(19, s.length()) as String;
        var sign = 1;
        var p = tail.find("+");
        if (p == null) {
            p = tail.find("-");
            sign = -1;
        }
        if (p != null && tail.length() >= p + 6) {
            var oh = two(tail, p + 1, p + 3);
            var om = two(tail, p + 4, p + 6);
            if (oh != null && om != null) {
                off = sign * (oh * 3600 + om * 60);
            }
        }
        // Days since 1970-01-01 (Howard Hinnant's days_from_civil).
        if (mo <= 2) {
            y -= 1;
        }
        var era = (y >= 0 ? y : y - 399) / 400;
        var yoe = y - era * 400;
        var mp = mo > 2 ? mo - 3 : mo + 9;
        var doy = (153 * mp + 2) / 5 + d - 1;
        var doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        var days = era * 146097 + doe - 719468;
        return days * 86400 + h * 3600 + mi * 60 + se - off;
    }

    function two(s as String, a as Number, b as Number) as Number? {
        var part = s.substring(a, b);
        return part == null ? null : (part as String).toNumberWithBase(10);
    }

    //! Warnings first, then watches, advisories and statements.
    function rank(event as String) as Number {
        if (event.find("Warning") != null) {
            return 3;
        }
        if (event.find("Watch") != null) {
            return 2;
        }
        if (event.find("Advisory") != null) {
            return 1;
        }
        return 0;
    }

    //! Alerts from the NWS GeoJSON response, at most 6, most serious first.
    function alertsFromJson(data as Dictionary) as Array? {
        var feats = data["features"];
        if (!(feats instanceof Array)) {
            return null;
        }
        var out = [];
        for (var i = 0; i < feats.size() && out.size() < 6; i++) {
            var f = feats[i];
            if (!(f instanceof Dictionary)) {
                continue;
            }
            var p = f["properties"];
            if (p instanceof Dictionary) {
                var ends = p["ends"];
                addAlert(out, p["event"], ends != null ? ends : p["expires"], p["headline"]);
            }
        }
        return sortAlerts(out);
    }

    //! The same from the raw text of the response, for phones that won't hand
    //! "application/geo+json" over as JSON. Each alert's properties start with
    //! "@type": "wx:Alert".
    function alertsFromText(body as String) as Array {
        var out = [];
        var mark = "wx:Alert";
        var rest = body;
        var i = rest.find(mark);
        while (i != null && out.size() < 6) {
            rest = rest.substring(i + mark.length(), rest.length()) as String;
            i = rest.find(mark);
            var chunk = (i == null) ? rest : (rest.substring(0, i) as String);
            var ends = jsonStr(chunk, "ends");
            addAlert(out, jsonStr(chunk, "event"), ends != null ? ends : jsonStr(chunk, "expires"), jsonStr(chunk, "headline"));
        }
        return sortAlerts(out);
    }

    //! The string value of "key" in a JSON fragment, or null (missing or null).
    function jsonStr(chunk as String, key as String) as String? {
        var k = chunk.find("\"" + key + "\":");
        if (k == null) {
            return null;
        }
        var s = chunk.substring(k + key.length() + 3, chunk.length()) as String;
        var q = s.find("\"");
        var comma = s.find(",");
        if (q == null || (comma != null && comma < q)) {
            return null;
        }
        s = s.substring(q + 1, s.length()) as String;
        var e = s.find("\"");
        return e == null ? null : (s.substring(0, e) as String);
    }

    function addAlert(out as Array, event as Object?, ends as Object?, headline as Object?) as Void {
        if (!(event instanceof String)) {
            return;
        }
        var t = isoToEpoch(ends);
        for (var i = 0; i < out.size(); i++) {
            var a = out[i] as Array;
            if ((event as String).equals(a[A_EVENT]) && t == a[A_ENDS]) {
                return; // the same alert for another zone
            }
        }
        out.add([event, t, headline instanceof String ? headline : null, rank(event as String)]);
    }

    function sortAlerts(list as Array) as Array {
        var out = [];
        for (var r = 3; r >= 0; r--) {
            for (var i = 0; i < list.size(); i++) {
                if ((list[i] as Array)[A_RANK] == r) {
                    out.add(list[i]);
                }
            }
        }
        return out;
    }

    //! A stored number that may be missing.
    function num(x as Object?) as Numeric {
        return (x instanceof Number || x instanceof Float || x instanceof Double || x instanceof Long) ? (x as Numeric) : 0;
    }
}
