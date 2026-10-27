import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
    case en, ru

    var id: String { rawValue }
    /// Always shown in its own language so it can be found whatever is selected.
    var title: String { self == .en ? "English" : "Русский" }
}

/// In-app language switch, independent of the system language and applied without a relaunch.
/// Keys are the English strings; translations live in Resources/<lang>.lproj/AWGBar.strings.
/// The table is not "Localizable" on purpose: SwiftUI looks up string literals there using the
/// system language, which would bypass this setting.
enum L10n {
    static let defaultsKey = "language"

    static var language: AppLanguage = AppLanguage(rawValue: UserDefaults.standard.string(forKey: defaultsKey) ?? "") ?? .en {
        didSet {
            UserDefaults.standard.set(language.rawValue, forKey: defaultsKey)
            bundle = load(language)
        }
    }

    private static var bundle = load(language)

    private static func load(_ language: AppLanguage) -> Bundle? {
        Bundle.main.path(forResource: language.rawValue, ofType: "lproj").flatMap(Bundle.init(path:))
    }

    static func string(_ key: String) -> String {
        bundle?.localizedString(forKey: key, value: key, table: "AWGBar") ?? key
    }
}

/// Use with Text(String)/Button(String), which render the result verbatim.
func L(_ key: String) -> String { L10n.string(key) }

func L(_ key: String, _ args: CVarArg...) -> String { String(format: L10n.string(key), arguments: args) }
