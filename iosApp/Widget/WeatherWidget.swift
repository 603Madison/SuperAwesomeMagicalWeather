import SwiftUI
import WidgetKit

/// What the app last wrote for the widgets (see IosApp.updateWidgets in the shared code).
struct WidgetData: Codable {
    let place: String
    let temperature: String
    let condition: String
    let symbol: String
    let high: String?
    let low: String?
    let headline: String?
    let updatedEpoch: Int64
}

private let appGroup = "group.app.northvane.weather"

private func loadData() -> WidgetData? {
    guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup),
          let data = try? Data(contentsOf: dir.appendingPathComponent("widget.json")) else { return nil }
    return try? JSONDecoder().decode(WidgetData.self, from: data)
}

struct WeatherEntry: TimelineEntry {
    let date: Date
    let data: WidgetData?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> WeatherEntry {
        WeatherEntry(date: .now, data: WidgetData(place: "Portland, ME", temperature: "54°", condition: "Clear", symbol: "sun.max.fill",
                                                  high: "62°", low: "53°", headline: "Dry for the next 18 hours.", updatedEpoch: 0))
    }

    func getSnapshot(in context: Context, completion: @escaping (WeatherEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : WeatherEntry(date: .now, data: loadData()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WeatherEntry>) -> Void) {
        // The app rewrites the data and asks for a reload whenever it refreshes; this is the fallback.
        let entry = WeatherEntry(date: .now, data: loadData())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(30 * 60))))
    }
}

struct WeatherWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: WeatherEntry

    var body: some View {
        if let d = entry.data {
            switch family {
            case .accessoryInline:
                Label("\(d.temperature) \(d.condition)", systemImage: d.symbol)
            case .accessoryCircular:
                VStack(spacing: 0) {
                    Image(systemName: d.symbol).font(.body)
                    Text(d.temperature).font(.headline)
                }
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Image(systemName: d.symbol)
                        Text(d.temperature).font(.headline)
                        if let hi = d.high, let lo = d.low { Text("\(hi)/\(lo)").font(.caption) }
                    }
                    Text(d.headline ?? d.condition).font(.caption).lineLimit(2)
                }
            case .systemMedium:
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(d.place).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(d.temperature).font(.system(size: 44, weight: .light))
                        Text(d.condition).font(.caption)
                        if let hi = d.high, let lo = d.low { Text("H \(hi)  L \(lo)").font(.caption2) }
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 6) {
                        Image(systemName: d.symbol).symbolRenderingMode(.multicolor).font(.system(size: 34))
                        if let h = d.headline { Text(h).font(.caption).multilineTextAlignment(.trailing).lineLimit(3) }
                    }
                }
                .foregroundStyle(.white)
            default:
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.place).font(.caption.weight(.semibold)).lineLimit(1)
                    HStack {
                        Text(d.temperature).font(.system(size: 36, weight: .light))
                        Spacer()
                        Image(systemName: d.symbol).symbolRenderingMode(.multicolor).font(.title2)
                    }
                    Text(d.headline ?? d.condition).font(.caption2).lineLimit(2)
                }
                .foregroundStyle(.white)
            }
        } else {
            Text("Open SuperAwesomeMagicalWeather to set up the widget.").font(.caption)
        }
    }
}

struct WeatherWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "WeatherWidget", provider: Provider()) { entry in
            WeatherWidgetView(entry: entry)
                .containerBackground(for: .widget) {
                    LinearGradient(colors: [Color(red: 0.18, green: 0.44, blue: 0.86), Color(red: 0.42, green: 0.69, blue: 0.95)],
                                   startPoint: .top, endPoint: .bottom)
                }
        }
        .configurationDisplayName("Weather")
        .description("Now and the day ahead for the place on your Today screen.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}

@main
struct WeatherWidgets: WidgetBundle {
    var body: some Widget {
        WeatherWidget()
    }
}
