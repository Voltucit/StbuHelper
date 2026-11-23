import UIKit
import WebKit

@MainActor
final class PortalController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let provider: CampusProvider
    private let initial: URL
    private let http: OfficialHTTP
    private let onConnected: (Bool) throws -> Void
    private let onVerified: (() throws -> Void)?
    private let web: WKWebView
    private var connected = false
    private var openedLogin = false
    private let status = UILabel()
    private let oauthHosts: Set<String> = ["open.weixin.qq.com", "long.open.weixin.qq.com", "lp.open.weixin.qq.com",
        "mp.weixin.qq.com", "graph.qq.com", "connect.qq.com", "xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com",
        "ptlogin2.qq.com", "ui.ptlogin2.qq.com"]

    init(provider: CampusProvider, url: URL?, http: OfficialHTTP,
         onVerified: (() throws -> Void)? = nil, onConnected: @escaping (Bool) throws -> Void) {
        self.provider = provider
        initial = url ?? provider.home
        self.http = http
        self.onConnected = onConnected
        self.onVerified = onVerified
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        web = WKWebView(frame: .zero, configuration: config)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = provider.label
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "返回", style: .done, target: self, action: #selector(close))
        if onVerified != nil {
            title = "学习通验证"
            let retry = UIBarButtonItem(title: "验证后重试", style: .plain, target: self, action: #selector(retryAfterVerification))
            retry.isEnabled = false
            navigationItem.leftBarButtonItem = retry
        }
        web.navigationDelegate = self
        web.uiDelegate = self
        status.text = "正在打开官方页面"
        status.font = .preferredFont(forTextStyle: .caption1)
        status.textColor = .secondaryLabel
        status.numberOfLines = 0
        let stack = UIStackView(arrangedSubviews: [status, web])
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        status.setContentHuggingPriority(.required, for: .vertical)
        view.addSubview(stack)
        let area = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo: area.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: area.bottomAnchor), stack.leadingAnchor.constraint(equalTo: area.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: area.trailingAnchor)])
        Task {
            let cookies = (try? http.cookies(provider)) ?? []
            for entry in cookies { if let cookie = entry.native { await web.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) } }
            web.load(URLRequest(url: initial))
        }
    }

    @objc private func close() { dismiss(animated: true) }

    @objc private func retryAfterVerification() {
        guard let onVerified, let url = web.url, provider.allows(url),
              let retry = navigationItem.leftBarButtonItem else { return }
        retry.isEnabled = false
        Task {
            defer { retry.isEnabled = true }
            let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
            guard web.url == url else { return }
            guard hasLearningSession(cookies) else {
                status.text = "请先在官方页面完成登录与验证。"
                return
            }
            do {
                try http.remember(cookies, provider: provider, freshLogin: false)
                try onVerified()
                dismiss(animated: true)
            } catch { status.text = error.localizedDescription }
        }
    }

    private func allowed(_ url: URL) -> Bool {
        provider.allows(url) || (url.scheme == "https" && url.user == nil && url.password == nil &&
            (url.port == nil || url.port == 443) && oauthHosts.contains(url.host?.lowercased() ?? ""))
    }

    private func hasLearningSession(_ cookies: [HTTPCookie]) -> Bool {
        cookies.contains {
            CampusProvider.learning.owns($0.domain.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased()) &&
                ["_uid", "UID", "PUID"].contains($0.name)
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if allowed(url) { decisionHandler(.allow); return }
        if url.scheme == "http", var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
           parts.port == nil || parts.port == 80 {
            parts.scheme = "https"
            parts.port = nil
            if action.targetFrame?.isMainFrame != false, let upgraded = parts.url, allowed(upgraded),
               action.request.httpMethod == "GET" {
                decisionHandler(.cancel); webView.load(URLRequest(url: upgraded)); return
            }
        }
        decisionHandler(.cancel)
        if action.targetFrame?.isMainFrame != false {
            if action.navigationType == .linkActivated && ["weixin", "mqq", "mqqapi"].contains(url.scheme ?? "") {
                UIApplication.shared.open(url)
            } else { status.text = "此链接已拦截，请返回官方登录页" }
        }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if action.targetFrame == nil, let url = action.request.url, allowed(url) { webView.load(action.request) }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let url = webView.url, provider.allows(url) else { return }
        if onVerified != nil {
            status.text = "请在官方页面完成验证码，再点「验证后重试」。"
            navigationItem.leftBarButtonItem?.isEnabled = true
            return
        }
        status.text = url.host
        if ["/cas/login", "/xtgl/login_slogin.html"].contains(url.path) ||
            (url.host == "passport2.chaoxing.com" && url.path == "/login") {
            openedLogin = true
            connected = false
        }
        let homeReached = provider == .school
            ? url.host == "jwxt.stbu.edu.cn" && url.path.contains("index_initMenu")
            : url.host == "i.chaoxing.com" && url.path.hasPrefix("/base")
        guard homeReached || connected else { return }
        Task {
            let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
            guard webView.url == url else { return }
            if provider == .learning && !hasLearningSession(cookies) { return }
            do {
                try http.remember(cookies, provider: provider, freshLogin: openedLogin)
                if !connected {
                    try onConnected(openedLogin)
                    connected = true
                    openedLogin = false
                    status.text = "已连接。返回川工商小助手可查看同步结果。"
                }
            } catch { status.text = error.localizedDescription }
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { status.text = "官方页面暂时无法打开" }
    }
}
