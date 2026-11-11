import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//! SuperAwesomeMagicalWeather for Garmin watches: a watch app with a glance.
//! The same class starts the app, the glance and the background refresh, so it
//! is available to all three.
(:background :glance)
class WeatherApp extends Application.AppBase {
    private var _view = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    (:typecheck([disableBackgroundCheck, disableGlanceCheck]))
    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        WxUi.scheduleRefresh();
        var view = new MainView();
        _view = view;
        return [view, new MainDelegate(view)];
    }

    (:typecheck(disableBackgroundCheck))
    function getGlanceView() as [WatchUi.GlanceView] or [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null {
        return [new WeatherGlance()];
    }

    function getServiceDelegate() as [System.ServiceDelegate] {
        return [new RefreshService()];
    }

    //! The background refresh finished (it saves straight to Storage).
    (:typecheck(disableBackgroundCheck))
    function onBackgroundData(data as Application.PersistableType) as Void {
        WatchUi.requestUpdate();
    }

    (:typecheck(disableBackgroundCheck))
    function onStorageChanged() as Void {
        WatchUi.requestUpdate();
    }

    //! Settings changed in the Garmin Connect app.
    (:typecheck([disableBackgroundCheck, disableGlanceCheck]))
    function onSettingsChanged() as Void {
        if (_view != null) {
            WxUi.scheduleRefresh();
            (_view as MainView).settingsChanged();
        }
        WatchUi.requestUpdate();
    }
}

//! The temporal event: refresh the saved data for the glance every 30 minutes
//! (or hour), using the last known position or the saved place.
(:background)
class RefreshService extends System.ServiceDelegate {
    private var _fetcher as Fetcher? = null;

    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() as Void {
        var loc = WxData.storedLocation();
        if (loc == null) {
            Background.exit(null);
            return;
        }
        _fetcher = new Fetcher(true, method(:onDone));
        (_fetcher as Fetcher).start(loc[0] as Numeric, loc[1] as Numeric, loc[2] as String?);
    }

    function onDone(code as Number) as Void {
        Background.exit(code == 200);
    }
}
