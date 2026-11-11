import Toybox.Lang;
import Toybox.Test;

// Unit tests for the parsing and wording helpers. They are compiled only with
// `monkeyc -t` (or the Monkey C extension's Run Tests) and run in the
// simulator; see docs/GARMIN.md.

(:test)
function testIsoToEpoch(logger as Test.Logger) as Boolean {
    return WxData.isoToEpoch("2026-11-13T10:00:00-05:00") == 1794582000
        && WxData.isoToEpoch("2026-11-11T13:00:00-09:00") == 1794434400
        && WxData.isoToEpoch("2026-03-01T00:00:00+00:00") == 1772323200
        && WxData.isoToEpoch("2024-02-29T23:59:59Z") == 1709251199
        && WxData.isoToEpoch(null) == null
        && WxData.isoToEpoch("soon") == null;
}

(:test)
function testParseLatLon(logger as Test.Logger) as Boolean {
    var ll = WxData.parseLatLon("43.66, -70.26");
    return ll != null && (ll[0] as Float) > 43.6 && (ll[1] as Float) < -70.2
        && WxData.parseLatLon("Portland, ME") == null
        && WxData.parseLatLon("Portland") == null
        && WxData.parseLatLon("95,10") == null;
}

(:test)
function testAlertsFromText(logger as Test.Logger) as Boolean {
    var body = "{\"features\": [{\"properties\": {\"@type\": \"wx:Alert\", \"expires\": \"2026-11-12T04:00:00-05:00\", "
        + "\"ends\": null, \"event\": \"Winter Weather Advisory\", \"headline\": \"Winter Weather Advisory until 1PM\"}}, "
        + "{\"properties\": {\"@type\": \"wx:Alert\", \"ends\": \"2026-11-13T10:00:00-05:00\", "
        + "\"event\": \"Winter Storm Warning\", \"headline\": \"Winter Storm Warning until Friday\"}}]}";
    var list = WxData.alertsFromText(body);
    logger.debug("alerts = " + list);
    if (list.size() != 2) {
        return false;
    }
    var first = list[0] as Array;
    var second = list[1] as Array;
    return "Winter Storm Warning".equals(first[WxData.A_EVENT])
        && first[WxData.A_ENDS] == 1794582000
        && first[WxData.A_RANK] == 3
        && "Winter Weather Advisory".equals(second[WxData.A_EVENT])
        && second[WxData.A_ENDS] == WxData.isoToEpoch("2026-11-12T04:00:00-05:00");
}

(:test)
function testRank(logger as Test.Logger) as Boolean {
    return WxData.rank("Tornado Warning") == 3 && WxData.rank("Flood Watch") == 2
        && WxData.rank("Wind Advisory") == 1 && WxData.rank("Special Weather Statement") == 0;
}

(:test)
function testRainSoon(logger as Test.Logger) as Boolean {
    var now = 1794418200;
    var times = [now, now + 900, now + 1800, now + 2700];
    var dry = WxText.rainSoon([times, [0, 0, 0, 0], [0, 0, 0, 0]], now);
    var starts = WxText.rainSoon([times, [0, 0, 0.4, 0.6], [0, 0, 0, 0]], now);
    var stops = WxText.rainSoon([times, [0.5, 0.2, 0, 0], [0, 0, 0, 0]], now);
    var all = WxText.rainSoon([times, [0.5, 0.5, 0.5, 0.5], [0.6, 0.6, 0.6, 0.6]], now);
    return dry != null && dry[0] == WxText.DRY && "Dry next hour".equals(WxText.rainLine(dry))
        && starts != null && starts[0] == WxText.START && starts[1] == now + 1800
        && (WxText.rainLine(starts) as String).find("Rain ~") == 0
        && stops != null && stops[0] == WxText.STOP && stops[1] == now + 1800
        && (WxText.rainLine(stops) as String).find("Rain until ~") == 0
        && all != null && "Snow all hour".equals(WxText.rainLine(all))
        && WxText.rainSoon(null, now) == null
        && WxText.rainSoon([times, [0, 0, 0, 0], null], now + 7200) == null;
}

//! A forecast array like Fetcher.parse() saves, °F, starting at `now`.
//! (:debug) keeps it out of release builds; it isn't a test itself.
(:debug)
function forecastAt(now as Number, codes as Array, pops as Array, gusts as Array, highs as Array) as Array {
    var times = [];
    var temps = [];
    for (var i = 0; i < 12; i++) {
        times.add(now + i * 3600);
        temps.add(45);
    }
    return [now, false, [45, codes[0], 1, gusts[0]],
        [times, temps, pops, codes, gusts],
        [[now, now + 86400], highs, [35, 36], [3, 3], [0, 0]],
        null, null];
}

(:test)
function testHeadline(logger as Test.Logger) as Boolean {
    var now = 1794418200;
    var zeros = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    var calm = [5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5];
    var clear = WxText.headline(forecastAt(now, zeros, zeros, calm, [50, 52]), now, null);
    var later = WxText.headline(forecastAt(now, [3, 3, 3, 61, 61, 61, 3, 3, 3, 3, 3, 3], [0, 0, 10, 80, 80, 70, 10, 0, 0, 0, 0, 0], calm, [50, 52]), now, null);
    var snowing = WxText.headline(forecastAt(now, [73, 73, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3], [90, 90, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0], calm, [30, 31]), now, null);
    var windy = WxText.headline(forecastAt(now, zeros, zeros, [10, 20, 41, 38, 20, 10, 5, 5, 5, 5, 5, 5], [50, 52]), now, null);
    var colder = WxText.headline(forecastAt(now, zeros, zeros, calm, [60, 45]), now, null);
    logger.debug([clear, later, snowing, windy, colder].toString());
    return "Dry for the next 12 hours".equals(clear)
        && later.find("Rain from ") == 0
        && snowing.find("Snow until about ") == 0
        && windy.find("Gusts to 41 mph around ") == 0
        && "Much colder tomorrow, high 45°".equals(colder);
}

(:test)
function testCondition(logger as Test.Logger) as Boolean {
    return "Clear".equals(WxText.condition(0)) && "Snow".equals(WxText.condition(75))
        && "Thunderstorms".equals(WxText.condition(95)) && "Freezing rain".equals(WxText.condition(66))
        && WxText.kind(66) == 3 && WxText.kind(3) == 0 && WxText.kind(81) == 1
        && "Wintry mix".equals(WxText.noun(3)) && "Snow".equals(WxText.noun(2));
}
