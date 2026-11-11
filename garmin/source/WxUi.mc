import Toybox.Activity;
import Toybox.Application.Storage;
import Toybox.Background;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.Position;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

//! Helpers for the app's own screens (not loaded by the glance or the
//! background). Everything is drawn with dc so one layout fits round,
//! semi-octagon (Instinct) and rectangular (Venu X1) screens of any size.
module WxUi {
    //! Background refresh every 30 or 60 minutes, or none, per the setting.
    function scheduleRefresh() as Void {
        if (!(Toybox has :Background)) {
            return;
        }
        var minutes = WxData.prop("refresh", 30);
        var current = Background.getTemporalEventRegisteredTime();
        if (minutes <= 0) {
            if (current != null) {
                Background.deleteTemporalEvent();
            }
            return;
        }
        if (minutes < 30) {
            minutes = 30;
        }
        if (current instanceof Time.Duration && (current as Time.Duration).value() == minutes * 60) {
            return;
        }
        Background.registerForTemporalEvent(new Time.Duration(minutes * 60));
    }

    //! The watch's last known position as [lat, lon], or null.
    function currentFix() as Array? {
        var loc = null;
        var info = Position.getInfo();
        if (info != null && info.accuracy != Position.QUALITY_NOT_AVAILABLE) {
            loc = info.position;
        }
        if (loc == null && (Toybox has :Activity)) {
            var a = Activity.getActivityInfo();
            if (a != null) {
                loc = a.currentLocation;
            }
        }
        if (loc == null) {
            return null;
        }
        var ll = (loc as Position.Location).toDegrees();
        // No fix reads as 180°, 180°.
        if (ll[0] < -90 || ll[0] > 90 || ll[1] < -180 || ll[1] > 180 || (ll[0] == 0 && ll[1] == 0)) {
            return null;
        }
        return [ll[0], ll[1]];
    }

    function forecast() as Array? {
        var wx = Storage.getValue(WxData.WX);
        return (wx instanceof Array && wx.size() == 7) ? (wx as Array) : null;
    }

    //! Alerts still in effect (possibly none).
    function alerts(now as Number) as Array {
        var out = [];
        var al = Storage.getValue(WxData.AL);
        if (al instanceof Array && al.size() == 2 && al[1] instanceof Array) {
            var list = al[1] as Array;
            for (var i = 0; i < list.size(); i++) {
                var ends = (list[i] as Array)[WxData.A_ENDS];
                if (ends == null || (ends as Number) > now) {
                    out.add(list[i]);
                }
            }
        }
        return out;
    }

    //! "us" (outside the US), "na" (couldn't load) or null.
    function alertProblem() as String? {
        var al = Storage.getValue(WxData.AL);
        if (al instanceof Array && al.size() == 2 && al[1] instanceof String) {
            return al[1] as String;
        }
        return null;
    }

    function isRound() as Boolean {
        return System.getDeviceSettings().screenShape != System.SCREEN_SHAPE_RECTANGLE;
    }

    //! Instinct's small round window, or null.
    function subscreen() as Graphics.BoundingBox? {
        if (WatchUi has :getSubscreen) {
            var s = WatchUi.getSubscreen();
            if (s != null && s.x != null && s.y != null) {
                return s;
            }
        }
        return null;
    }

    //! [top, bottom] of the area for text.
    function area(dc as Graphics.Dc) as Array<Number> {
        var h = dc.getHeight();
        var m = isRound() ? h / 10 : h / 25;
        var top = m;
        var s = subscreen();
        if (s != null && (s.y as Number) + s.height > top) {
            top = (s.y as Number) + s.height;
        }
        return [top, h - m];
    }

    //! Usable width for wrapped text.
    function textWidth(dc as Graphics.Dc) as Number {
        return dc.getWidth() * (isRound() ? 80 : 92) / 100;
    }

    //! Half the screen's width at height y (less on a round screen).
    function halfWidthAt(dc as Graphics.Dc, y as Number) as Number {
        var r = dc.getWidth() / 2;
        if (!isRound()) {
            return r;
        }
        var dy = y - dc.getHeight() / 2;
        var sq = r * r - dy * dy;
        return sq <= 0 ? 0 : Math.sqrt(sq).toNumber();
    }

    //! Text wrapped to at most `lines` lines, cut with "..." when longer.
    function fit(dc as Graphics.Dc, text as String, font as Graphics.FontType, width as Number, lines as Number) as String {
        var s = Graphics.fitTextToArea(text, font, width, dc.getFontHeight(font) * lines, true);
        return s == null ? text : (s as String);
    }

    //! Word wrap into separate lines (for scrolling).
    function wrap(dc as Graphics.Dc, text as String, font as Graphics.FontType, width as Number) as Array<String> {
        var lines = [] as Array<String>;
        var line = "";
        var rest = text;
        while (rest.length() > 0) {
            var sp = rest.find(" ");
            var word = (sp == null) ? rest : (rest.substring(0, sp) as String);
            rest = (sp == null) ? "" : (rest.substring(sp + 1, rest.length()) as String);
            var candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && dc.getTextWidthInPixels(candidate, font) > width) {
                lines.add(line);
                line = word;
            } else {
                line = candidate;
            }
        }
        if (line.length() > 0) {
            lines.add(line);
        }
        return lines;
    }

    //! Draws rows of centered text, each [text, font, color, priority], as a
    //! block centered between top and bottom. Rows that don't fit are left
    //! out, least important first, so small screens show what matters most.
    function drawRows(dc as Graphics.Dc, rows as Array, top as Number, bottom as Number) as Void {
        var n = rows.size();
        var heights = new [n];
        var keep = new [n];
        var total = 0;
        for (var i = 0; i < n; i++) {
            var row = rows[i] as Array;
            heights[i] = dc.getFontHeight(row[1] as Graphics.FontType) * lineCount(row[0] as String);
            keep[i] = true;
            total += heights[i] as Number;
        }
        while (total > bottom - top) {
            var worst = -1;
            for (var i = 0; i < n; i++) {
                if ((keep[i] as Boolean) && (worst < 0 || ((rows[i] as Array)[3] as Number) < ((rows[worst] as Array)[3] as Number))) {
                    worst = i;
                }
            }
            if (worst < 0) {
                break;
            }
            keep[worst] = false;
            total -= heights[worst] as Number;
        }
        var y = top + (bottom - top - total) / 2;
        if (y < top) {
            y = top;
        }
        var cx = dc.getWidth() / 2;
        for (var i = 0; i < n; i++) {
            if (keep[i] as Boolean) {
                var row = rows[i] as Array;
                dc.setColor(row[2] as Number, Graphics.COLOR_TRANSPARENT);
                dc.drawText(cx, y, row[1] as Graphics.FontType, row[0] as String, Graphics.TEXT_JUSTIFY_CENTER);
                y += heights[i] as Number;
            }
        }
    }

    function lineCount(s as String) as Number {
        var n = 1;
        var rest = s;
        var i = rest.find("\n");
        while (i != null) {
            n++;
            rest = rest.substring(i + 1, rest.length()) as String;
            i = rest.find("\n");
        }
        return n;
    }

    //! A small table: a title, then rows of three columns. left is
    //! right-aligned to the middle of the screen, mid and right follow it;
    //! leftShort replaces left where the screen is too narrow (round edges).
    //! Uses the largest font that fits every row; on tiny screens shows
    //! as many rows as fit.
    function drawTable(dc as Graphics.Dc, title as String, left as Array<String>, leftShort as Array<String>, mid as Array<String>, right as Array<String>, rightColor as Number) as Void {
        var box = area(dc);
        var top = box[0];
        var avail = box[1] - top;
        var tf = Graphics.FONT_XTINY;
        var th = dc.getFontHeight(tf);
        var n = left.size();
        var fonts = [Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY];
        var f = Graphics.FONT_XTINY;
        for (var i = 0; i < fonts.size(); i++) {
            if (th + n * dc.getFontHeight(fonts[i]) <= avail) {
                f = fonts[i];
                break;
            }
        }
        var fh = dc.getFontHeight(f);
        var rows = (avail - th) / fh;
        if (rows > n) {
            rows = n;
        }
        var cx = dc.getWidth() / 2;
        var y = top + (avail - th - rows * fh) / 2;
        dc.setColor(WxText.col(WxText.COLOR_DIM), Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, tf, title, Graphics.TEXT_JUSTIFY_CENTER);
        y += th;

        var gap = fh / 3;
        var midW = 0;
        for (var i = 0; i < rows; i++) {
            var w = dc.getTextWidthInPixels(mid[i], f);
            if (w > midW) {
                midW = w;
            }
        }
        for (var i = 0; i < rows; i++) {
            var half = halfWidthAt(dc, y + fh / 2) - 2;
            var l = left[i];
            if (dc.getTextWidthInPixels(l, f) > half - gap) {
                l = leftShort[i];
            }
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx - gap, y, f, l, Graphics.TEXT_JUSTIFY_RIGHT);
            dc.drawText(cx + gap, y, f, mid[i], Graphics.TEXT_JUSTIFY_LEFT);
            var rx = cx + gap + midW + gap;
            if (right[i].length() > 0 && rx + dc.getTextWidthInPixels(right[i], f) <= cx + half) {
                dc.setColor(rightColor, Graphics.COLOR_TRANSPARENT);
                dc.drawText(rx, y, f, right[i], Graphics.TEXT_JUSTIFY_LEFT);
            }
            y += fh;
        }
    }

    //! Which page this is: dots on the right edge, or "2/5" in Instinct's
    //! small window.
    function drawPager(dc as Graphics.Dc, page as Number, count as Number, useSubscreen as Boolean) as Void {
        var s = subscreen();
        if (s != null) {
            if (useSubscreen) {
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                dc.drawText((s.x as Number) + s.width / 2, (s.y as Number) + s.height / 2, Graphics.FONT_SMALL,
                    (page + 1).toString() + "/" + count, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
            return;
        }
        var r = dc.getWidth() / 90;
        if (r < 2) {
            r = 2;
        }
        var step = r * 3 + 2;
        var x = dc.getWidth() - r * 2 - 3;
        var y = dc.getHeight() / 2 - (count - 1) * step / 2;
        for (var i = 0; i < count; i++) {
            dc.setColor(i == page ? Graphics.COLOR_WHITE : WxText.col(WxText.COLOR_DIM), Graphics.COLOR_TRANSPARENT);
            if (i == page) {
                dc.fillCircle(x, y + i * step, r);
            } else {
                dc.drawCircle(x, y + i * step, r);
            }
        }
    }
}
