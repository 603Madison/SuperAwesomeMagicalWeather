import SwiftUI
import WidgetKit
import Shared

/// Starts the shared app: background refresh and notifications must be set up before launch finishes.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        NotificationCenter.default.addObserver(forName: Notification.Name(IosApp.shared.WIDGETS_CHANGED), object: nil, queue: .main) { _ in
            WidgetCenter.shared.reloadAllTimelines()
        }
        IosApp.shared.onLaunch()
        return true
    }
}

@main
struct WeatherApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ComposeView()
                // The shared screens draw edge to edge and pad for the notch and home bar themselves.
                .ignoresSafeArea()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { IosApp.shared.onForeground() }
            if phase == .background { IosApp.shared.updateWidgets() }
        }
    }
}

/// The shared Compose screens, hosted in SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
