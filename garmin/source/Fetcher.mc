import Toybox.Application.Storage;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;

//! Downloads the forecast (Open-Meteo) and then the NWS alerts for one place,
//! through the phone's Garmin Connect app, and saves both in Storage. Used by
//! the app and by the background refresh. Requests ask only for the fields
//! the watch shows, about 1.5 KB of JSON, to stay clear of -402 (response too
//! large) and the background's memory limit.
(:background)
class Fetcher {
    const UA = "SuperAwesomeMagicalWeather-Garmin (github.com/603Madison/SuperAwesomeMagicalWeather)";

    private var _background as Boolean;
    private var _done as Method(code as Number) as Void;
    private var _lat as Numeric = 0;
    private var _lon as Numeric = 0;
    private var _place as String? = null;
    private var _metric as Boolean = false;
    private var _asText as Boolean = false;

    //! done is called once with 200, or the forecast's error code.
    function initialize(background as Boolean, done as Method(code as Number) as Void) {
        _background = background;
        _done = done;
    }

    function start(lat as Numeric, lon as Numeric, place as String?) as Void {
        _lat = lat;
        _lon = lon;
        _place = place;
        _metric = WxData.isMetric();
        var url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat.format("%.4f") + "&longitude=" + lon.format("%.4f")
            + "&current=temperature_2m,weather_code,is_day,wind_gusts_10m"
            + "&hourly=temperature_2m,precipitation_probability,weather_code,wind_gusts_10m&forecast_hours=12"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=5"
            + "&minutely_15=precipitation,snowfall&forecast_minutely_15=4"
            + "&timezone=auto&timeformat=unixtime"
            + (_metric ? "" : "&temperature_unit=fahrenheit&wind_speed_unit=mph");
        Communications.makeWebRequest(url, null, {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        }, method(:onForecast));
    }

    function onForecast(code as Number, data as Dictionary or String or Null) as Void {
        var wx = (code == 200 && data instanceof Dictionary) ? parse(data as Dictionary) : null;
        data = null;
        if (wx == null) {
            Storage.setValue(WxData.ERR, [Time.now().value(), code == 200 ? -400 : code]);
            _done.invoke(code == 200 ? -400 : code);
            return;
        }
        Storage.setValue(WxData.WX, wx);
        Storage.deleteValue(WxData.ERR);
        wx = null;
        requestAlerts();
    }

    //! The forecast as plain arrays (see WxData.F_*), or null if a part is missing.
    function parse(d as Dictionary) as Array? {
        var c = d["current"];
        var h = d["hourly"];
        var dd = d["daily"];
        var m = d["minutely_15"];
        if (!(c instanceof Dictionary) || !(h instanceof Dictionary) || !(dd instanceof Dictionary)) {
            return null;
        }
        if (!(h["time"] instanceof Array) || !(dd["time"] instanceof Array)) {
            return null;
        }
        var q15 = null;
        if (m instanceof Dictionary && m["time"] instanceof Array && m["precipitation"] instanceof Array) {
            q15 = [m["time"], m["precipitation"], m["snowfall"]];
        }
        return [
            Time.now().value(),
            _metric,
            [c["temperature_2m"], c["weather_code"], c["is_day"], c["wind_gusts_10m"]],
            [h["time"], h["temperature_2m"], h["precipitation_probability"], h["weather_code"], h["wind_gusts_10m"]],
            [dd["time"], dd["temperature_2m_max"], dd["temperature_2m_min"], dd["weather_code"], dd["precipitation_probability_max"]],
            q15,
            _place
        ];
    }

    function requestAlerts() as Void {
        // NWS answers with "application/geo+json". If the phone won't parse
        // that as JSON, onAlerts() asks again for the raw text.
        Communications.makeWebRequest(
            "https://api.weather.gov/alerts/active?point=" + _lat.format("%.4f") + "," + _lon.format("%.4f"),
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :headers => { "User-Agent" => UA, "Accept" => "application/geo+json" },
                :responseType => _asText ? Communications.HTTP_RESPONSE_CONTENT_TYPE_TEXT_PLAIN : Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            method(:onAlerts)
        );
    }

    function onAlerts(code as Number, data as Dictionary or String or Null) as Void {
        var list = null;
        if (code == 200 && data instanceof Dictionary) {
            list = WxData.alertsFromJson(data as Dictionary);
        } else if (code == 200 && data instanceof String) {
            list = WxData.alertsFromText(data as String);
        } else if ((code == -400 || code == -1002) && !_asText) {
            data = null;
            _asText = true;
            requestAlerts();
            return;
        }
        data = null;
        var now = Time.now().value();
        if (list != null) {
            noteWarnings(list);
            Storage.setValue(WxData.AL, [now, list]);
        } else if (code == 400 || code == 404) {
            // NWS only covers the US: "point is out of bounds".
            Storage.setValue(WxData.AL, [now, "us"]);
        } else {
            // Keep a recent good answer rather than blanking it (the
            // background may simply have run short of memory).
            var old = Storage.getValue(WxData.AL);
            if (!(old instanceof Array) || !((old as Array)[1] instanceof Array) || now - ((old as Array)[0] as Number) > 3 * 3600) {
                Storage.setValue(WxData.AL, [now, "na"]);
            }
        }
        _done.invoke(200);
    }

    //! Remembers the current warnings. From the background, offers to open the
    //! app when one is new (once per warning, if the setting is on).
    function noteWarnings(list as Array) as Void {
        var seen = Storage.getValue(WxData.SEEN);
        var keys = [];
        var fresh = null;
        for (var i = 0; i < list.size(); i++) {
            var a = list[i] as Array;
            if (a[WxData.A_RANK] != 3) {
                continue;
            }
            var key = (a[WxData.A_EVENT] as String) + "|" + a[WxData.A_ENDS];
            keys.add(key);
            if (fresh == null && !contains(seen, key)) {
                fresh = a[WxData.A_EVENT];
            }
        }
        Storage.setValue(WxData.SEEN, keys);
        if (_background && fresh != null && WxData.flag("warn", true)) {
            Background.requestApplicationWake((fresh as String) + "\nOpen weather?");
        }
    }

    function contains(list as Object?, key as String) as Boolean {
        if (list instanceof Array) {
            for (var i = 0; i < list.size(); i++) {
                if (key.equals(list[i])) {
                    return true;
                }
            }
        }
        return false;
    }
}
