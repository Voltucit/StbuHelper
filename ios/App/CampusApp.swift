import UIKit
import WebKit
import UniformTypeIdentifiers
import ImageIO
import UserNotifications

@main
@MainActor
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var window: UIWindow?
    private var campus: CampusController?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        do {
            let controller = try CampusController()
            campus = controller
            window.rootViewController = controller
            if let url = options?[.url] as? URL { _ = controller.openWidget(url) }
            UNUserNotificationCenter.current().delegate = self
        } catch {
            let failed = UIViewController()
            failed.view.backgroundColor = .systemBackground
            let label = UILabel()
            label.text = error.localizedDescription
            label.numberOfLines = 0
            label.textAlignment = .center
            label.translatesAutoresizingMaskIntoConstraints = false
            failed.view.addSubview(label)
            NSLayoutConstraint.activate([label.centerYAnchor.constraint(equalTo: failed.view.centerYAnchor),
                label.leadingAnchor.constraint(equalTo: failed.view.layoutMarginsGuide.leadingAnchor),
                label.trailingAnchor.constraint(equalTo: failed.view.layoutMarginsGuide.trailingAnchor)])
            window.rootViewController = failed
        }
        self.window = window
        window.makeKeyAndVisible()
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) { campus?.resume() }

    func application(_ application: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        campus?.openWidget(url) ?? false
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async {
            _ = self.campus?.openWidget(URL(string: "stbu-helper://tasks")!)
            completionHandler()
        }
    }
}

@MainActor
private final class Bridge: NSObject, WKScriptMessageHandlerWithReply {
    weak var owner: CampusController?

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard message.frameInfo.isMainFrame, message.frameInfo.request.url == LocalResources.index,
              let owner, let body = message.body as? Object, let method = body["method"] as? String,
              let arguments = body["args"] as? Object, let bytes = try? JSONSerialization.data(withJSONObject: arguments),
              bytes.count <= 2 * 1024 * 1024 else {
            replyHandler(nil, "请求来源或内容不受支持"); return
        }
        Task {
            do { replyHandler(try await owner.call(method, arguments), nil) }
            catch is SessionExpired { replyHandler(nil, "账号登录已失效，请重新连接") }
            catch let error as AppError { replyHandler(nil, error.localizedDescription) }
            catch { replyHandler(nil, "操作未完成，请检查网络或本机存储后重试") }
        }
    }
}

@MainActor
final class CampusController: UIViewController, WKNavigationDelegate, UIDocumentPickerDelegate {
    private let store: CampusStore
    private let academic: Academic
    private let wallet: Wallet
    private let web: WKWebView
    private let photos: URL
    private let bridge: Bridge
    private var pendingRoute = ""
    private var ready = false
    private enum PickerPurpose { case background, importing, exporting }
    private var pickerPurpose: PickerPurpose?
    private var pickerResult: CheckedContinuation<Object, Error>?

    init() throws {
        let store = try CampusStore()
        self.store = store
        let academic = Academic(store: store)
        self.academic = academic
        wallet = Wallet(http: academic.http)
        let photos = store.directory.appendingPathComponent("background", isDirectory: true)
        self.photos = photos
        try FileManager.default.createDirectory(at: photos, withIntermediateDirectories: true)
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.setURLSchemeHandler(try LocalResources(photos: photos), forURLScheme: "stbu-app")
        let bridge = Bridge()
        self.bridge = bridge
        config.userContentController.addScriptMessageHandler(bridge, contentWorld: .page, name: "stbu")
        web = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil)
        bridge.owner = self
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        web.navigationDelegate = self
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.scrollView.bounces = false
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)
        let area = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([web.topAnchor.constraint(equalTo: area.topAnchor),
            web.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            web.leadingAnchor.constraint(equalTo: area.leadingAnchor), web.trailingAnchor.constraint(equalTo: area.trailingAnchor)])
        appearance()
        web.load(URLRequest(url: LocalResources.index))
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(action.targetFrame?.isMainFrame == true && action.request.url == LocalResources.index ? .allow : .cancel)
    }

    func resume() {
        academic.autoStart()
        academic.publishWidgets()
        if ready { web.evaluateJavaScript("window.onNativeResume && window.onNativeResume()") }
    }

    func openWidget(_ url: URL) -> Bool {
        guard url.scheme == "stbu-helper", let route = url.host, ["tasks", "week"].contains(route),
              url.user == nil, url.password == nil, url.path.isEmpty else { return false }
        pendingRoute = route
        if ready {
            web.evaluateJavaScript("window.onNotificationRoute && window.onNotificationRoute('\(route)')") { [weak self] result, _ in
                if result as? Bool == true, self?.pendingRoute == route { self?.pendingRoute = "" }
            }
        }
        return true
    }

    private func appearance() {
        let dark = store.preferences["dark"] as? Bool == true
        overrideUserInterfaceStyle = dark ? .dark : .light
        let color = dark ? UIColor(red: 0.08, green: 0.11, blue: 0.09, alpha: 1)
                         : UIColor(red: 0.97, green: 0.97, blue: 0.96, alpha: 1)
        view.backgroundColor = color
        web.isOpaque = false
        web.backgroundColor = color
        web.scrollView.backgroundColor = color
    }

    func call(_ method: String, _ args: Object) async throws -> Any {
        switch method {
        case "bootstrap":
            let reminders = await academic.reminderStatus()
            let data = try store.read()
            let page = pendingRoute
            pendingRoute = ""
            ready = true
            return ["data": data, "credential": ["saved": false], "life": wallet.status(), "app": [
                "name": Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "川工商小助手",
                "versionName": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
            ], "preferences": store.preferences, "academic": academic.accounts, "sync": academic.state,
                "tracking": academic.trackingStatus(), "reminders": reminders, "launchPage": page] as Object
        case "saveAppPreferences":
            let result = store.savePreferences(args)
            appearance()
            if args["emptyDayMessage"] != nil || args["emptyTasksMessage"] != nil {
                academic.publishWidgets()
            }
            return result
        case "chooseAppBackground": return try await pick(.background, types: [.jpeg, .png, .webP])
        case "clearAppBackground":
            if let old = store.settings.string(forKey: "background") {
                store.settings.removeObject(forKey: "background")
                try? FileManager.default.removeItem(at: photos.appendingPathComponent(old))
            }
            return ["preferences": store.preferences]
        case "openPortal":
            guard let provider = CampusProvider(rawValue: args.text("kind")) else { throw AppError("无法识别登录入口") }
            try portal(provider, nil)
            return Object()
        case "openTask":
            guard let url = URL(string: args.text("url")), CampusProvider.learning.allows(url) else { throw AppError("作业链接无效") }
            try portal(.learning, url)
            return Object()
        case "academicLogout": return try academic.logout(provider(args))
        case "syncAcademics":
            let selected = args.text("provider", "all")
            guard selected == "all" || CampusProvider(rawValue: selected) != nil else { throw AppError("请选择同步账号") }
            let scope = args["homeworkOnly"] as? Bool == true ? "homework" : args["coursesOnly"] as? Bool == true ? "courses"
                : args["currentWeekOnly"] as? Bool == true ? "current" : "all"
            return academic.start(selected, scope: scope)
        case "academicState":
            var state = academic.state
            state["updatedAt"] = try store.read().text("updatedAt")
            return state
        case "homeworkTracking": return academic.trackingStatus()
        case "refreshHomeworkCourses": return try await academic.refreshCourses()
        case "saveHomeworkTracking": return try academic.saveTracking(args)
        case "configureReminders": return try await academic.configureReminders(args["enabled"] as? Bool == true)
        case "notificationSettings":
            if let url = URL(string: UIApplication.openSettingsURLString) { await UIApplication.shared.open(url) }
            return Object()
        case "saveSettings":
            guard let updates = args["settings"] as? Object else { throw AppError("设置格式不正确") }
            var data = try store.read()
            data["settings"] = (data["settings"] as? Object ?? [:]).merging(updates) { _, new in new }
            return try save(data)
        case "saveCourse", "deleteCourse":
            var data = try store.read()
            let course = args["course"] as? Object
            let id = course?.text("id") ?? args.text("id")
            guard !id.isEmpty else { throw AppError("课程编号无效") }
            var rows = data.rows("courses").filter { $0.text("id") != id }
            if method == "saveCourse", var course { course["source"] = "manual"; rows.append(course) }
            data["courses"] = rows
            return try save(data)
        case "saveData":
            guard let data = args["data"] as? Object else { throw AppError("请选择有效备份") }
            return try restore(data)
        case "clearAcademicData":
            guard academic.state["running"] as? Bool != true else { throw AppError("请先等待同步完成") }
            try store.clearData()
            academic.publishWidgets()
            Task { await academic.scheduleReminders() }
            return try store.read()
        case "importFile": return try await pick(.importing, types: [.json])
        case "exportFile": return try await export()
        case "lifeStatus": return wallet.status()
        case "lifeLogin": return try await wallet.login(args)
        case "lifeLogout": return try wallet.logout()
        case "lifeRefresh": return try await wallet.balance()
        case "lifeBills": return try await wallet.bills(args.text("month"))
        case "lifeRecharge":
            var link = URLComponents()
            link.scheme = "alipays"
            link.host = "platformapi"
            link.path = "/startapp"
            link.queryItems = [URLQueryItem(name: "appId", value: "2018090661238647"),
                               URLQueryItem(name: "page", value: "pages/recharge/recharge")]
            guard wallet.status()["connected"] as? Bool == true, let url = link.url,
                  await UIApplication.shared.open(url) else { throw AppError("请确认已连接趣智校园并安装支付宝") }
            return ["opened": true]
        default: throw AppError("此操作暂未在 iOS 上提供")
        }
    }

    private func provider(_ args: Object) throws -> CampusProvider {
        guard let provider = CampusProvider(rawValue: args.text("provider")) else { throw AppError("账号类型无效") }
        return provider
    }

    private func portal(_ provider: CampusProvider, _ url: URL?) throws {
        guard presentedViewController == nil else { throw AppError("请先关闭当前窗口") }
        let portal = PortalController(provider: provider, url: url, http: academic.http) { [weak self] freshLogin in
            guard let self else { return }
            try self.academic.connected(provider, freshLogin: freshLogin)
            self.resume()
        }
        let navigation = UINavigationController(rootViewController: portal)
        navigation.modalPresentationStyle = .fullScreen
        present(navigation, animated: true)
    }

    @discardableResult private func save(_ value: Object) throws -> Object {
        try store.save(value)
        academic.publishWidgets()
        return value
    }

    private func restore(_ value: Object) throws -> Object {
        guard academic.state["running"] as? Bool != true else { throw AppError("请先等待同步完成") }
        let saved = try save(value)
        Task { await academic.scheduleReminders() }
        return saved
    }

    private func pick(_ purpose: PickerPurpose, types: [UTType]) async throws -> Object {
        try await showPicker(UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true), purpose)
    }

    private func showPicker(_ picker: UIDocumentPickerViewController, _ purpose: PickerPurpose) async throws -> Object {
        guard pickerResult == nil, presentedViewController == nil else { throw AppError("请先关闭当前窗口") }
        return try await withCheckedThrowingContinuation { continuation in
            pickerResult = continuation
            pickerPurpose = purpose
            picker.delegate = self
            picker.allowsMultipleSelection = false
            picker.modalPresentationStyle = .fullScreen
            present(picker, animated: true)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finishPicker(.success(["cancelled": true]))
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        do {
            guard let purpose = pickerPurpose, let url = urls.first else { throw AppError("所选文件不可用") }
            switch purpose {
            case .importing:
                let bytes = try readDocument(url, limit: 2 * 1024 * 1024)
                guard let data = try JSONSerialization.jsonObject(with: bytes) as? Object else { throw AppError("请选择有效备份") }
                _ = try restore(data)
                resume()
            case .background:
                try importBackground(url)
            case .exporting: break
            }
            finishPicker(.success(["cancelled": false, "preferences": store.preferences]))
        } catch { finishPicker(.failure(error)) }
    }

    private func finishPicker(_ result: Result<Object, Error>) {
        let continuation = pickerResult
        pickerResult = nil
        pickerPurpose = nil
        continuation?.resume(with: result)
    }

    private func export() async throws -> Object {
        var data = try store.read()
        data["tasks"] = data.rows("tasks").map { row -> Object in var row = row; row.removeValue(forKey: "url"); return row }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("StbuExport-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("StbuHelper.json")
        try JSONSerialization.data(withJSONObject: data).write(to: file, options: .atomic)
        return try await showPicker(UIDocumentPickerViewController(forExporting: [file], asCopy: true), .exporting)
    }

    private func readDocument(_ url: URL, limit: Int) throws -> Data {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let bytes = try handle.read(upToCount: limit + 1) ?? Data()
        guard !bytes.isEmpty, bytes.count <= limit else { throw AppError("文件为空或超过大小限制") }
        return bytes
    }

    private func importBackground(_ url: URL) throws {
        let bytes = try readDocument(url, limit: 8 * 1024 * 1024)
        guard let source = CGImageSourceCreateWithData(bytes as CFData, nil), let identifier = CGImageSourceGetType(source),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let w = props[kCGImagePropertyPixelWidth as String] as? Int, let h = props[kCGImagePropertyPixelHeight as String] as? Int,
              (1...8192).contains(w), (1...8192).contains(h), w * h <= 16_777_216,
              let type = [UTType.jpeg.identifier: "jpg", UTType.png.identifier: "png", UTType.webP.identifier: "webp"][identifier as String] else {
            throw AppError("请选择限制范围内的 JPG、PNG 或 WebP 图片")
        }
        let name = "background-" + UUID().uuidString.lowercased() + "." + type
        try bytes.write(to: photos.appendingPathComponent(name), options: .atomic)
        let old = store.settings.string(forKey: "background")
        store.settings.set(name, forKey: "background")
        if let old { try? FileManager.default.removeItem(at: photos.appendingPathComponent(old)) }
    }
}
