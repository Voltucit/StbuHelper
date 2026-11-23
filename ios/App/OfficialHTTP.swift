import Foundation
import SwiftSoup
import WebKit

enum CampusProvider: String, CaseIterable {
    case learning, school

    var label: String { self == .learning ? "学习通" : "学校教务系统" }
    var home: URL {
        URL(string: self == .learning
            ? "https://i.chaoxing.com/base?ws=1"
            : "https://jwxt.stbu.edu.cn/xtgl/index_initMenu.html?jsdm=xs&echarts=1")!
    }
    func owns(_ host: String) -> Bool {
        let domain = self == .learning ? "chaoxing.com" : "stbu.edu.cn"
        return host == domain || host.hasSuffix("." + domain)
    }
    func allows(_ url: URL) -> Bool {
        url.scheme == "https" && url.user == nil && url.password == nil &&
            (url.port == nil || url.port == 443) && owns(url.host?.lowercased() ?? "")
    }
}

struct SessionExpired: Error {}

struct LearningVerificationRequired: LocalizedError {
    var errorDescription: String? { "学习通要求人工验证，请打开官方验证页" }
}

struct LearningRateLimited: LocalizedError {
    var errorDescription: String? { "学习通限制了请求频率，请稍后再同步" }
}

struct PortalCookie: Codable, Equatable {
    let name: String
    let value: String
    let domain: String
    let path: String
    let expires: Date?
    let secure: Bool
    let httpOnly: Bool

    init(_ cookie: HTTPCookie) {
        name = cookie.name
        value = cookie.value
        domain = cookie.domain.lowercased()
        path = cookie.path
        expires = cookie.expiresDate
        secure = cookie.isSecure
        httpOnly = cookie.isHTTPOnly
    }

    var key: String { "\(domain)|\(path)|\(name)" }
    var valid: Bool { expires.map { $0 > Date() } ?? true }

    var native: HTTPCookie? {
        var props: [HTTPCookiePropertyKey: Any] = [.name: name, .value: value, .domain: domain, .path: path]
        if let expires { props[.expires] = expires }
        if secure { props[.secure] = "TRUE" }
        if httpOnly { props[HTTPCookiePropertyKey("HttpOnly")] = "TRUE" }
        return HTTPCookie(properties: props)
    }

    func matches(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased(), valid else { return false }
        let cookieHost = domain.hasPrefix(".") ? String(domain.dropFirst()) : domain
        guard host == cookieHost || (domain.hasPrefix(".") && host.hasSuffix("." + cookieHost)) else { return false }
        let requestPath = url.path.isEmpty ? "/" : url.path
        return requestPath == path || requestPath.hasPrefix(path.hasSuffix("/") ? path : path + "/")
    }
}

final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class OfficialHTTP {
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Version/16.0 Mobile/15E148 Safari/604.1"
    static let mobileHomeworkURL = URL(string: "https://mooc1.chaoxing.com/mooc-ans/work/stu-work?ut=s")!
    private static let verificationHome = URL(string: "https://mooc1.chaoxing.com/antispiderShowVerify.ac")!
    private let settings: UserDefaults
    private let verificationKey = "learning.verificationRequired"
    private var verificationPage: URL?
    private let delegate = NoRedirects()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }()
    private var nextRequest = [String: Date]()
    private var learningBlockedUntil = Date.distantPast
    private var portalGeneration = [CampusProvider: Int]()

    init(settings: UserDefaults) { self.settings = settings }

    var learningVerificationRequired: Bool { settings.bool(forKey: verificationKey) }

    var learningVerificationURL: URL? {
        learningVerificationRequired ? (verificationPage ?? Self.verificationHome) : nil
    }

    func clearLearningVerification() {
        verificationPage = nil
        settings.removeObject(forKey: verificationKey)
    }

    private func requireLearningVerification(at url: URL) {
        if verificationPage == nil { verificationPage = url }
        settings.set(true, forKey: verificationKey)
    }

    static func form(_ fields: [String: String]) -> String {
        let safe = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        return fields.sorted { $0.key < $1.key }.map { key, value in
            (key.addingPercentEncoding(withAllowedCharacters: safe) ?? "") + "=" +
            (value.addingPercentEncoding(withAllowedCharacters: safe) ?? "")
        }.joined(separator: "&")
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let learning = CampusProvider.learning.owns(request.url?.host?.lowercased() ?? "")
        if learning && learningVerificationRequired { throw LearningVerificationRequired() }
        if learning && Date() < learningBlockedUntil { throw LearningRateLimited() }
        if let host = request.url?.host {
            let now = Date()
            let start = max(now, nextRequest[host] ?? now)
            nextRequest[host] = start.addingTimeInterval(0.2)
            let wait = start.timeIntervalSince(now)
            if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        }
        if learning && learningVerificationRequired { throw LearningVerificationRequired() }
        if learning && Date() < learningBlockedUntil { throw LearningRateLimited() }
        let (bytes, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppError("服务未返回数据") }
        if http.statusCode == 429 {
            if learning {
                learningBlockedUntil = Date().addingTimeInterval(60)
            } else {
                if let host = request.url?.host { nextRequest[host] = Date().addingTimeInterval(60) }
                throw AppError("请求较频繁，请稍后再同步")
            }
        }
        return (bytes, http)
    }

    func cookies(_ provider: CampusProvider) throws -> [PortalCookie] {
        (try Keychain.read("portal." + provider.rawValue, as: [PortalCookie].self) ?? []).filter(\.valid)
    }

    func remember(_ values: [HTTPCookie], provider: CampusProvider, freshLogin: Bool) throws {
        let owned = values.map(PortalCookie.init).filter { $0.valid && provider.owns($0.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))) }
        guard !owned.isEmpty else { throw AppError("官方页面尚未建立可复用的会话") }
        let learningIDs: Set<String> = ["_uid", "UID", "PUID"]
        let previous = provider == .learning ? try cookies(provider) : []
        let changed = provider == .learning && Set(previous.filter { learningIDs.contains($0.name) }.map(\.value)) !=
            Set(owned.filter { learningIDs.contains($0.name) }.map(\.value))
        try Keychain.write("portal." + provider.rawValue, owned)
        if freshLogin || changed { portalGeneration[provider, default: 0] += 1 }
    }

    func page(_ initial: URL, provider: CampusProvider, form fields: [String: String]? = nil, referer: URL? = nil) async throws -> (String, URL) {
        let generation = portalGeneration[provider, default: 0]
        var url = initial
        var body = fields
        for _ in 0..<9 {
            guard provider.allows(url) else { throw SessionExpired() }
            let path = url.path.lowercased()
            if path.contains("/cas/login") || path.contains("login_slogin") || (url.host == "passport2.chaoxing.com" && path.hasPrefix("/login")) {
                throw SessionExpired()
            }
            let saved = try cookies(provider)
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
            request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
            let matching = saved.filter { $0.matches(url) }.sorted { $0.path.count > $1.path.count }
            if !matching.isEmpty {
                request.setValue(matching.map { $0.name + "=" + $0.value }.joined(separator: "; "), forHTTPHeaderField: "Cookie")
            }
            if let referer, provider.allows(referer) { request.setValue(referer.absoluteString, forHTTPHeaderField: "Referer") }
            if let body {
                request.httpMethod = "POST"
                request.httpBody = Data(Self.form(body).utf8)
                request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
                if let host = url.host { request.setValue("https://" + host, forHTTPHeaderField: "Origin") }
            }
            let (data, response) = try await send(request)
            guard portalGeneration[provider, default: 0] == generation else { throw AppError("校园账号已变化，请重新同步") }
            let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, pair in
                if let key = pair.key as? String, let value = pair.value as? String { result[key] = value }
            }
            let received = HTTPCookie.cookies(withResponseHeaderFields: headers, for: url).map(PortalCookie.init)
            if !received.isEmpty {
                var merged = (try cookies(provider)).reduce(into: [String: PortalCookie]()) { $0[$1.key] = $1 }
                for cookie in received where provider.owns(cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))) {
                    merged[cookie.key] = cookie.valid ? cookie : nil
                }
                try Keychain.write("portal." + provider.rawValue, Array(merged.values))
            }
            if (300...399).contains(response.statusCode), let location = response.value(forHTTPHeaderField: "Location"),
               let next = URL(string: location, relativeTo: url)?.absoluteURL {
                if provider == .learning, let verification = Self.officialVerificationRedirect(next) {
                    requireLearningVerification(at: verification)
                    throw LearningVerificationRequired()
                }
                if [307, 308].contains(response.statusCode), body != nil, next.host != url.host {
                    throw AppError("官方 POST 请求跳转至其他域名")
                }
                url = next
                if [301, 302, 303].contains(response.statusCode) { body = nil }
                continue
            }
            let html = String(decoding: data, as: UTF8.self)
            if provider == .learning, html.contains("验证码"),
               let visible = try? SwiftSoup.parse(html).text(),
               visible.contains("验证码"),
               visible.range(of: "操作(?:受到?|已被)?限制|操作过于频繁|9010", options: .regularExpression) != nil {
                requireLearningVerification(at: body == nil ? url : Self.verificationHome)
                throw LearningVerificationRequired()
            }
            if provider == .learning && learningVerificationRequired { throw LearningVerificationRequired() }
            if response.statusCode == 429 { throw LearningRateLimited() }
            if response.statusCode == 401 { throw SessionExpired() }
            guard (200...299).contains(response.statusCode) else { throw AppError("官方服务暂未返回数据") }
            if html.range(of: #"<input[^>]+(?:type=["']password|id=["'](?:password|mm)["'])"#, options: .regularExpression) != nil &&
                html.range(of: "用户登录|统一身份认证|手机号/超星号|账号登录", options: .regularExpression) != nil { throw SessionExpired() }
            return (html, url)
        }
        throw AppError("官方登录跳转次数过多")
    }

    private static func officialVerificationRedirect(_ url: URL) -> URL? {
        guard url.path.lowercased().split(separator: "/").contains(where: { $0.hasPrefix("antispider") }),
              var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        if parts.scheme == "http", parts.port == nil || parts.port == 80 {
            parts.scheme = "https"
            parts.port = nil
        }
        guard let target = parts.url, CampusProvider.learning.allows(target) else { return nil }
        return target
    }
}
