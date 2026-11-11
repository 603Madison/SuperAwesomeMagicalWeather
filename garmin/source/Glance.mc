import Toybox.Application.Storage;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

//! The glance: "52° Mostly cloudy" and one line under it, which is a warning
//! in effect, else rain in the next hour ("Rain ~3:40"), else the headline.
//! It only reads what the app and the background refresh saved.
(:glance)
class WeatherGlance extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var font = (Graphics has :FONT_GLANCE) ? Graphics.FONT_GLANCE : Graphics.FONT_TINY;
        var w = dc.getWidth();
        var fh = dc.getFontHeight(font);
        var y = dc.getHeight() / 2;
        var line1 = "Weather";
        var line2 = "Open to set up";
        var color2 = Graphics.COLOR_WHITE;

        var wx = Storage.getValue(WxData.WX);
        if (wx instanceof Array && wx.size() == 7) {
            var now = Time.now().value();
            var cur = wx[WxData.F_CUR] as Array;
            line1 = WxText.deg(cur[0]) + " " + WxText.condition(WxData.num(cur[1]).toNumber());
            var soon = WxText.rainSoon(wx[WxData.F_Q15] as Array?, now);
            var rain = WxText.rainLine(soon);
            var warning = topWarning(now);
            if (warning != null) {
                line2 = warning;
                color2 = WxText.col(WxText.COLOR_WARNING);
            } else if (rain != null && (soon as Array)[0] != WxText.DRY) {
                line2 = rain;
                color2 = WxText.col(WxText.COLOR_RAIN);
            } else if (now - (wx[WxData.F_TIME] as Number) > 3 * 3600) {
                line2 = "Old data, from " + WxText.clock(wx[WxData.F_TIME] as Number);
                color2 = WxText.col(WxText.COLOR_DIM);
            } else {
                line2 = WxText.headline(wx, now, soon);
            }
        }

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, y - fh, font, fit(line1, font, w, fh), Graphics.TEXT_JUSTIFY_LEFT);
        dc.setColor(color2, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, y, font, fit(line2, font, w, fh), Graphics.TEXT_JUSTIFY_LEFT);
    }

    //! One line, cut with "..." if it's too long.
    function fit(text as String, font as Graphics.FontType, w as Number, h as Number) as String {
        var s = Graphics.fitTextToArea(text, font, w, h, true);
        return s == null ? text : (s as String);
    }

    //! The first warning still in effect, or null.
    function topWarning(now as Number) as String? {
        var al = Storage.getValue(WxData.AL);
        if (al instanceof Array && al.size() == 2 && al[1] instanceof Array) {
            var list = al[1] as Array;
            for (var i = 0; i < list.size(); i++) {
                var a = list[i] as Array;
                var ends = a[WxData.A_ENDS];
                if (a[WxData.A_RANK] == 3 && (ends == null || (ends as Number) > now)) {
                    return a[WxData.A_EVENT] as String;
                }
            }
        }
        return null;
    }
}
