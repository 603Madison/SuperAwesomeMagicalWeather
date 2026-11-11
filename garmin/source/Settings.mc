import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

//! Settings on the watch (Menu button, or hold it on touch watches). The
//! same settings appear in the Garmin Connect app when the app came from the
//! Connect IQ store; a sideloaded app can only be set up here.
module SettingsMenu {
    function open(view as MainView) as Void {
        var menu = new WatchUi.Menu2({ :title => "Settings" });
        menu.addItem(new WatchUi.MenuItem("Location", sourceLabel(), :source, null));
        if (WatchUi has :TextPicker) {
            menu.addItem(new WatchUi.MenuItem("Set place", placeLabel(), :place, null));
        }
        menu.addItem(new WatchUi.MenuItem("Units", unitsLabel(), :units, null));
        menu.addItem(new WatchUi.MenuItem("Background refresh", refreshLabel(), :refresh, null));
        menu.addItem(new WatchUi.MenuItem("Warning pop-ups", WxData.flag("warn", true) ? "On" : "Off", :warn, null));
        menu.addItem(new WatchUi.MenuItem("Update now", null, :now, null));
        WatchUi.pushView(menu, new SettingsDelegate(view, menu), WatchUi.SLIDE_UP);
    }

    function sourceLabel() as String {
        if (WxData.prop("source", 0) != 1) {
            return "Watch GPS";
        }
        var place = WxData.text("place");
        return place.length() > 0 ? place : "Saved place (not set)";
    }

    function placeLabel() as String {
        var place = WxData.text("place");
        return place.length() > 0 ? place : "Town or lat,lon";
    }

    function unitsLabel() as String {
        var u = WxData.prop("units", 0);
        return u == 1 ? "°F, mph" : (u == 2 ? "°C, km/h" : "Watch setting");
    }

    function refreshLabel() as String {
        var m = WxData.prop("refresh", 30);
        return m <= 0 ? "Off" : (m >= 60 ? "Every hour" : "Every 30 min");
    }

    //! Saves a typed place and switches to it.
    function setPlace(text as String) as Void {
        Properties.setValue("place", WxData.trim(text));
        Properties.setValue("source", 1);
        Storage.deleteValue(WxData.GEO);
    }
}

class SettingsDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;
    private var _menu as WatchUi.Menu2;
    private var _changed as Boolean = false;

    function initialize(view as MainView, menu as WatchUi.Menu2) {
        Menu2InputDelegate.initialize();
        _view = view;
        _menu = menu;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == :source) {
            if (WxData.prop("source", 0) == 1) {
                Properties.setValue("source", 0);
            } else if (WxData.text("place").length() > 0) {
                Properties.setValue("source", 1);
            } else if (WatchUi has :TextPicker) {
                askPlace();
                return;
            }
            item.setSubLabel(SettingsMenu.sourceLabel());
            _changed = true;
        } else if (id == :place) {
            askPlace();
            return;
        } else if (id == :units) {
            Properties.setValue("units", (WxData.prop("units", 0) + 1) % 3);
            item.setSubLabel(SettingsMenu.unitsLabel());
            _changed = true;
        } else if (id == :refresh) {
            var m = WxData.prop("refresh", 30);
            Properties.setValue("refresh", m <= 0 ? 30 : (m < 60 ? 60 : 0));
            item.setSubLabel(SettingsMenu.refreshLabel());
            WxUi.scheduleRefresh();
        } else if (id == :warn) {
            var on = !WxData.flag("warn", true);
            Properties.setValue("warn", on);
            item.setSubLabel(on ? "On" : "Off");
        } else if (id == :now) {
            WatchUi.popView(WatchUi.SLIDE_DOWN);
            _view.refresh();
            return;
        }
        WatchUi.requestUpdate();
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        if (_changed) {
            _view.settingsChanged();
        }
    }

    private function askPlace() as Void {
        WatchUi.pushView(new WatchUi.TextPicker(WxData.text("place")), new PlacePicker(self), WatchUi.SLIDE_LEFT);
    }

    //! Called by PlacePicker after a place was typed.
    function placeChanged() as Void {
        _changed = true;
        var i = _menu.findItemById(:source);
        if (i >= 0) {
            (_menu.getItem(i) as WatchUi.MenuItem).setSubLabel(SettingsMenu.sourceLabel());
        }
        i = _menu.findItemById(:place);
        if (i >= 0) {
            (_menu.getItem(i) as WatchUi.MenuItem).setSubLabel(SettingsMenu.placeLabel());
        }
    }
}

class PlacePicker extends WatchUi.TextPickerDelegate {
    private var _settings as SettingsDelegate;

    function initialize(settings as SettingsDelegate) {
        TextPickerDelegate.initialize();
        _settings = settings;
    }

    function onTextEntered(text as String, changed as Boolean) as Boolean {
        if (WxData.trim(text).length() > 0) {
            SettingsMenu.setPlace(text);
            _settings.placeChanged();
        }
        return true;
    }

    function onCancel() as Boolean {
        return true;
    }
}

//! Looks up a place name once with Open-Meteo's geocoder and saves the
//! answer (WxData.GEO). "Portland, ME", "Portland, Maine" and
//! "Portland, US" pick the right Portland; a bare name takes the biggest.
class Geocoder {
    // ",<abbreviation><state>," for US states, to match "Portland, ME".
    const STATES = ",ALAlabama,AKAlaska,AZArizona,ARArkansas,CACalifornia,COColorado,CTConnecticut,DEDelaware,DCDistrict of Columbia,FLFlorida,GAGeorgia,HIHawaii,IDIdaho,ILIllinois,INIndiana,IAIowa,KSKansas,KYKentucky,LALouisiana,MEMaine,MDMaryland,MAMassachusetts,MIMichigan,MNMinnesota,MSMississippi,MOMissouri,MTMontana,NENebraska,NVNevada,NHNew Hampshire,NJNew Jersey,NMNew Mexico,NYNew York,NCNorth Carolina,NDNorth Dakota,OHOhio,OKOklahoma,OROregon,PAPennsylvania,PRPuerto Rico,RIRhode Island,SCSouth Carolina,SDSouth Dakota,TNTennessee,TXTexas,UTUtah,VTVermont,VAVirginia,WAWashington,WVWest Virginia,WIWisconsin,WYWyoming,";

    private var _query as String;
    private var _name as String;
    private var _qualifier as String? = null;
    private var _done as Method(found as Boolean) as Void;

    function initialize(query as String, done as Method(found as Boolean) as Void) {
        _query = query;
        _name = query;
        _done = done;
        var c = query.find(",");
        if (c != null) {
            _name = WxData.trim(query.substring(0, c) as String);
            _qualifier = WxData.trim(query.substring(c + 1, query.length()) as String).toLower();
        }
    }

    function start() as Void {
        Communications.makeWebRequest(
            "https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&format=json&name=" + Communications.encodeURL(_name),
            null,
            { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON },
            method(:onResult)
        );
    }

    function onResult(code as Number, data as Dictionary or String or Null) as Void {
        if (code != 200 || !(data instanceof Dictionary)) {
            _done.invoke(false);
            return;
        }
        var results = (data as Dictionary)["results"];
        data = null;
        if (!(results instanceof Array) || results.size() == 0) {
            Storage.deleteValue(WxData.GEO);
            _done.invoke(true);  // looked up, nothing found
            return;
        }
        var pick = results[0] as Dictionary;
        if (_qualifier != null) {
            for (var i = 0; i < results.size(); i++) {
                if (matches(results[i] as Dictionary)) {
                    pick = results[i] as Dictionary;
                    break;
                }
            }
        }
        var name = pick["name"];
        Storage.setValue(WxData.GEO, [_query, pick["latitude"], pick["longitude"], name instanceof String ? name : _name]);
        _done.invoke(true);
    }

    private function matches(r as Dictionary) as Boolean {
        var q = _qualifier as String;
        var admin1 = r["admin1"];
        var keys = [r["country_code"], r["country"], admin1];
        for (var i = 0; i < keys.size(); i++) {
            if (keys[i] instanceof String && q.equals((keys[i] as String).toLower())) {
                return true;
            }
        }
        return admin1 instanceof String && q.length() == 2
            && STATES.find("," + q.toUpper() + (admin1 as String) + ",") != null;
    }
}
