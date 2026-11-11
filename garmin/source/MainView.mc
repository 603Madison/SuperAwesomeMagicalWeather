import Toybox.Application.Storage;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Position;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

//! The app: page 0 is now (temperature, condition, high/low, headline,
//! next-hour rain, alerts), then one page per NWS alert, hourly for the next
//! 8 hours and 5 days. Up/down (swipe on touch screens) moves between pages.
class MainView extends WatchUi.View {
    var page as Number = 0;
    private var _status as String? = null;
    private var _busy as Boolean = false;
    private var _again as Boolean = false;
    private var _fetcher as Fetcher? = null;
    private var _geocoder as Geocoder? = null;
    private var _gpsTimer as Timer.Timer? = null;

    function initialize() {
        View.initialize();
    }

    function onShow() as Void {
        var wx = WxUi.forecast();
        if (wx == null || Time.now().value() - (wx[WxData.F_TIME] as Number) > 15 * 60) {
            refresh();
        }
    }

    // ---- Refreshing ----

    function refresh() as Void {
        if (_busy) {
            _again = true;
            return;
        }
        _busy = true;
        _again = false;
        _status = "Updating...";
        WatchUi.requestUpdate();
        if (WxData.usesPlace()) {
            var place = WxData.storedLocation();
            if (place != null) {
                fetch(place);
            } else {
                _geocoder = new Geocoder(WxData.text("place"), method(:onPlace));
                (_geocoder as Geocoder).start();
            }
            return;
        }
        var fix = WxUi.currentFix();
        if (fix != null) {
            Storage.setValue(WxData.LOC, fix);
            fetch([fix[0], fix[1], null]);
            return;
        }
        var stored = WxData.storedLocation();
        if (stored != null) {
            fetch(stored);
            return;
        }
        // No position yet: ask the GPS once, for up to two minutes.
        _status = "Finding location...";
        Position.enableLocationEvents(Position.LOCATION_ONE_SHOT, method(:onPosition));
        _gpsTimer = new Timer.Timer();
        (_gpsTimer as Timer.Timer).start(method(:onGpsTimeout), 120000, false);
    }

    function onPosition(info as Position.Info) as Void {
        stopGps();
        var loc = info.position;
        if (loc == null) {
            finish("No GPS position");
            return;
        }
        var ll = loc.toDegrees();
        Storage.setValue(WxData.LOC, [ll[0], ll[1]]);
        fetch([ll[0], ll[1], null]);
    }

    function onGpsTimeout() as Void {
        Position.enableLocationEvents(Position.LOCATION_DISABLE, null);
        _gpsTimer = null;
        finish("No GPS position. Go outside and try again");
    }

    private function stopGps() as Void {
        if (_gpsTimer != null) {
            (_gpsTimer as Timer.Timer).stop();
            _gpsTimer = null;
        }
    }

    function onPlace(found as Boolean) as Void {
        _geocoder = null;
        var place = found ? WxData.storedLocation() : null;
        if (place == null) {
            finish(found ? "Place not found" : "Couldn't look up place");
            return;
        }
        fetch(place);
    }

    private function fetch(loc as Array) as Void {
        _status = "Updating...";
        _fetcher = new Fetcher(false, method(:onFetched));
        (_fetcher as Fetcher).start(loc[0] as Numeric, loc[1] as Numeric, loc[2] as String?);
        WatchUi.requestUpdate();
    }

    function onFetched(code as Number) as Void {
        _fetcher = null;
        finish(code == 200 ? null : WxText.errorText(code));
    }

    private function finish(status as String?) as Void {
        _busy = false;
        _status = status;
        WatchUi.requestUpdate();
        if (_again) {
            refresh();
        }
    }

    function settingsChanged() as Void {
        page = 0;
        refresh();
    }

    // ---- Paging ----

    function pageCount() as Number {
        return 3 + WxUi.alerts(Time.now().value()).size();
    }

    function flip(step as Number) as Void {
        var n = WxUi.forecast() == null ? 1 : pageCount();
        page = (page + step + n) % n;
        WatchUi.requestUpdate();
    }

    //! Select (or tap): an alert page opens the alert; elsewhere, refresh.
    function select() as Void {
        var alerts = WxUi.alerts(Time.now().value());
        if (page >= 1 && page <= alerts.size()) {
            var view = new AlertView(alerts[page - 1] as Array);
            WatchUi.pushView(view, new AlertDelegate(view), WatchUi.SLIDE_LEFT);
        } else {
            refresh();
        }
    }

    // ---- Drawing ----

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var now = Time.now().value();
        var box = WxUi.area(dc);
        var wx = WxUi.forecast();
        if (wx == null) {
            var rows = [
                ["Weather", Graphics.FONT_MEDIUM, Graphics.COLOR_WHITE, 9],
                [WxUi.fit(dc, _status != null ? (_status as String) : "Press Start to update", Graphics.FONT_XTINY, WxUi.textWidth(dc), 2), Graphics.FONT_XTINY, Graphics.COLOR_WHITE, 8]
            ];
            if (!WxData.usesPlace()) {
                rows.add(["Uses the watch's position", Graphics.FONT_XTINY, WxText.col(WxText.COLOR_DIM), 1]);
            }
            WxUi.drawRows(dc, rows, box[0], box[1]);
            return;
        }
        var alerts = WxUi.alerts(now);
        var count = 3 + alerts.size();
        if (page >= count) {
            page = 0;
        }
        if (page == 0) {
            drawNow(dc, box, wx, alerts, now);
        } else if (page <= alerts.size()) {
            drawAlert(dc, box, alerts[page - 1] as Array, page, alerts.size(), now);
        } else if (page == alerts.size() + 1) {
            drawHourly(dc, wx, now);
        } else {
            drawDaily(dc, wx);
        }
        WxUi.drawPager(dc, page, count, page != 0);
    }

    private function drawNow(dc as Graphics.Dc, box as Array<Number>, wx as Array, alerts as Array, now as Number) as Void {
        var dim = WxText.col(WxText.COLOR_DIM);
        var cur = wx[WxData.F_CUR] as Array;
        var daily = wx[WxData.F_DAILY] as Array;
        var soon = WxText.rainSoon(wx[WxData.F_Q15] as Array?, now);
        var width = WxUi.textWidth(dc);
        var rows = [];

        var place = wx[WxData.F_PLACE];
        if (place instanceof String) {
            rows.add([WxUi.fit(dc, place as String, Graphics.FONT_XTINY, width, 1), Graphics.FONT_XTINY, dim, 2]);
        }
        var sub = WxUi.subscreen();
        if (sub != null) {
            // Instinct: the temperature goes in the small round window.
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText((sub.x as Number) + sub.width / 2, (sub.y as Number) + sub.height / 2, Graphics.FONT_MEDIUM,
                WxText.deg(cur[0]), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            rows.add([WxText.deg(cur[0]), Graphics.FONT_LARGE, Graphics.COLOR_WHITE, 10]);
        }
        rows.add([WxUi.fit(dc, WxText.condition(WxData.num(cur[1]).toNumber()), Graphics.FONT_SMALL, width, 1), Graphics.FONT_SMALL, Graphics.COLOR_WHITE, 9]);

        var highs = daily[1] as Array?;
        var lows = daily[2] as Array?;
        if (highs != null && lows != null && highs.size() > 0 && lows.size() > 0) {
            rows.add(["H " + WxText.deg(highs[0]) + "  L " + WxText.deg(lows[0]), Graphics.FONT_XTINY, dim, 6]);
        }

        var headline = WxText.headline(wx, now, soon);
        if (headline.length() > 0) {
            rows.add([WxUi.fit(dc, headline, Graphics.FONT_XTINY, width, 2), Graphics.FONT_XTINY, Graphics.COLOR_WHITE, 8]);
        }

        var rain = WxText.rainLine(soon);
        if (rain != null) {
            var wet = (soon as Array)[0] != WxText.DRY;
            rows.add([rain, Graphics.FONT_XTINY, wet ? WxText.col(WxText.COLOR_RAIN) : dim, wet ? 7 : 5]);
        }

        if (alerts.size() > 0) {
            var top = alerts[0] as Array;
            var text = top[WxData.A_EVENT] as String;
            if (alerts.size() > 1) {
                text += " +" + (alerts.size() - 1);
            }
            rows.add([WxUi.fit(dc, text, Graphics.FONT_XTINY, width, 1), Graphics.FONT_XTINY, WxText.alertColor(top[WxData.A_RANK] as Number), 8]);
        } else if ("na".equals(WxUi.alertProblem())) {
            rows.add(["Alerts unavailable", Graphics.FONT_XTINY, dim, 3]);
        }

        rows.add([statusText(wx), Graphics.FONT_XTINY, dim, _status != null ? 5 : 4]);
        WxUi.drawRows(dc, rows, box[0], box[1]);
    }

    private function statusText(wx as Array) as String {
        if (_status != null) {
            return _status as String;
        }
        var fetched = wx[WxData.F_TIME] as Number;
        var err = Storage.getValue(WxData.ERR);
        if (err instanceof Array && (err[0] as Number) > fetched) {
            return WxText.errorText(err[1] as Number);
        }
        return "Updated " + WxText.clock(fetched);
    }

    private function drawAlert(dc as Graphics.Dc, box as Array<Number>, alert as Array, index as Number, count as Number, now as Number) as Void {
        var rank = alert[WxData.A_RANK] as Number;
        var ends = alert[WxData.A_ENDS];
        var touch = System.getDeviceSettings().isTouchScreen;
        WxUi.drawRows(dc, [
            ["Alert " + index + " of " + count, Graphics.FONT_XTINY, WxText.alertColor(rank), 5],
            [WxUi.fit(dc, alert[WxData.A_EVENT] as String, Graphics.FONT_SMALL, WxUi.textWidth(dc), 3), Graphics.FONT_SMALL, Graphics.COLOR_WHITE, 10],
            [ends == null ? "Until further notice" : "Until " + WxText.whenLabel(ends as Number, now), Graphics.FONT_XTINY, Graphics.COLOR_WHITE, 8],
            [touch ? "Tap to read" : "Start to read", Graphics.FONT_XTINY, WxText.col(WxText.COLOR_DIM), 3]
        ], box[0], box[1]);
    }

    private function drawHourly(dc as Graphics.Dc, wx as Array, now as Number) as Void {
        var h = wx[WxData.F_HOURLY] as Array;
        var times = h[0] as Array;
        var temps = h[1] as Array?;
        var pops = h[2] as Array?;
        var left = [] as Array<String>;
        var mid = [] as Array<String>;
        var right = [] as Array<String>;
        for (var i = 0; i < times.size() && left.size() < 8; i++) {
            var t = times[i] as Number;
            if (t + 3600 <= now) {
                continue;
            }
            left.add(left.size() == 0 ? "Now" : WxText.hourLabel(t));
            mid.add(WxText.deg(temps != null && i < temps.size() ? temps[i] : null));
            right.add(popText(pops, i));
        }
        if (left.size() == 0) {
            WxUi.drawRows(dc, [["No hourly data yet", Graphics.FONT_XTINY, Graphics.COLOR_WHITE, 1]], WxUi.area(dc)[0], WxUi.area(dc)[1]);
            return;
        }
        WxUi.drawTable(dc, "Next hours", left, left, mid, right, WxText.col(WxText.COLOR_RAIN));
    }

    private function drawDaily(dc as Graphics.Dc, wx as Array) as Void {
        var d = wx[WxData.F_DAILY] as Array;
        var times = d[0] as Array;
        var highs = d[1] as Array?;
        var lows = d[2] as Array?;
        var codes = d[3] as Array?;
        var pops = d[4] as Array?;
        var left = [] as Array<String>;
        var leftShort = [] as Array<String>;
        var mid = [] as Array<String>;
        var right = [] as Array<String>;
        for (var i = 0; i < times.size() && i < 5; i++) {
            var day = i == 0 ? "Today" : WxText.dayName(times[i] as Number);
            var cond = codes != null && i < codes.size() ? WxText.shortCondition(WxData.num(codes[i]).toNumber()) : "";
            leftShort.add(day);
            left.add(cond.length() > 0 ? day + " " + cond : day);
            mid.add(WxText.deg(highs != null && i < highs.size() ? highs[i] : null) + "/" + WxText.deg(lows != null && i < lows.size() ? lows[i] : null));
            right.add(popText(pops, i));
        }
        WxUi.drawTable(dc, "5 days", left, leftShort, mid, right, WxText.col(WxText.COLOR_RAIN));
    }

    //! "40%" when there's a real chance of rain or snow, else "".
    private function popText(pops as Array?, i as Number) as String {
        if (pops == null || i >= pops.size() || pops[i] == null) {
            return "";
        }
        var p = WxText.round(WxData.num(pops[i]));
        return p >= 10 ? p.toString() + "%" : "";
    }
}

class MainDelegate extends WatchUi.BehaviorDelegate {
    private var _view as MainView;

    function initialize(view as MainView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() as Boolean {
        _view.flip(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.flip(-1);
        return true;
    }

    function onSelect() as Boolean {
        _view.select();
        return true;
    }

    function onMenu() as Boolean {
        SettingsMenu.open(_view);
        return true;
    }
}

//! One alert's full NWS headline, scrolled with up/down.
class AlertView extends WatchUi.View {
    private var _alert as Array;
    private var _lines as Array<String>? = null;
    private var _first as Number = 0;
    private var _visible as Number = 1;

    function initialize(alert as Array) {
        View.initialize();
        _alert = alert;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var box = WxUi.area(dc);
        var width = WxUi.textWidth(dc);
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        if (_lines == null) {
            var text = _alert[WxData.A_HEADLINE];
            _lines = WxUi.wrap(dc, text instanceof String ? (text as String) : (_alert[WxData.A_EVENT] as String), font, width);
        }
        var lines = _lines as Array<String>;
        var cx = dc.getWidth() / 2;
        var y = box[0];
        var title = WxUi.fit(dc, _alert[WxData.A_EVENT] as String, Graphics.FONT_SMALL, width, 2);
        dc.setColor(WxText.alertColor(_alert[WxData.A_RANK] as Number), Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_SMALL, title, Graphics.TEXT_JUSTIFY_CENTER);
        y += dc.getFontHeight(Graphics.FONT_SMALL) * WxUi.lineCount(title) + fh / 3;

        _visible = (box[1] - y) / fh;
        if (_visible < 1) {
            _visible = 1;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        for (var i = _first; i < lines.size() && i < _first + _visible; i++) {
            dc.drawText(cx, y, font, lines[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += fh;
        }
        if (_first + _visible < lines.size()) {
            // More below: a small arrow at the bottom.
            var by = box[1] + (dc.getHeight() - box[1]) / 3;
            var s = fh / 4;
            dc.fillPolygon([[cx - s, by], [cx + s, by], [cx, by + s]]);
        }
    }

    function scroll(step as Number) as Void {
        if (_lines == null) {
            return;
        }
        var page = _visible > 1 ? _visible - 1 : 1;
        var next = _first + step * page;
        var max = (_lines as Array<String>).size() - _visible;
        if (next > max) {
            next = max;
        }
        if (next < 0) {
            next = 0;
        }
        _first = next;
        WatchUi.requestUpdate();
    }
}

class AlertDelegate extends WatchUi.BehaviorDelegate {
    private var _view as AlertView;

    function initialize(view as AlertView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onNextPage() as Boolean {
        _view.scroll(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        _view.scroll(-1);
        return true;
    }

    function onSelect() as Boolean {
        _view.scroll(1);
        return true;
    }
}
