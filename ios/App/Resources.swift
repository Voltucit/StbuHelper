import Foundation
import UniformTypeIdentifiers
import WebKit

final class LocalResources: NSObject, WKURLSchemeHandler {
    static let index = URL(string: "stbu-app://local/frontend/index.html")!
    private let bundleRoot: URL
    private let photos: URL

    init(photos: URL) throws {
        guard let resources = Bundle.main.resourceURL else { throw AppError("应用资源不可用") }
        let root = resources.appendingPathComponent("frontend", isDirectory: true)
        guard FileManager.default.fileExists(atPath: root.appendingPathComponent("index.html").path) else {
            throw AppError("构建未包含共享前端资源")
        }
        bundleRoot = root.resolvingSymlinksInPath()
        self.photos = photos
    }

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        do {
            guard let url = task.request.url, url.scheme == "stbu-app", url.host == "local",
                  url.port == nil, url.user == nil, url.password == nil, (task.request.httpMethod ?? "GET") == "GET" else {
                throw URLError(.unsupportedURL)
            }
            let path = url.pathComponents.dropFirst()
            guard path.count >= 2, !path.contains("..") else { throw URLError(.fileDoesNotExist) }
            let tail = path.dropFirst()
            let root: URL
            if path.first == "frontend" {
                root = bundleRoot
            } else if path.first == "background", tail.count == 1,
                      tail.first?.range(of: #"^background-[a-f0-9-]+\.(jpg|png|webp)$"#, options: .regularExpression) != nil {
                root = photos
            } else { throw URLError(.fileDoesNotExist) }
            let file = tail.reduce(root) { $0.appendingPathComponent($1) }.standardizedFileURL.resolvingSymlinksInPath()
            guard file.path.hasPrefix(root.standardizedFileURL.resolvingSymlinksInPath().path + "/") else { throw URLError(.fileDoesNotExist) }
            var bytes = try Data(contentsOf: file)
            let mime: String
            switch file.pathExtension {
            case "html":
                mime = "text/html"
                let html = String(decoding: bytes, as: UTF8.self)
                    .replacingOccurrences(of: "script-src 'self'", with: "script-src 'self' stbu-app:")
                    .replacingOccurrences(of: "style-src 'self'", with: "style-src 'self' stbu-app:")
                    .replacingOccurrences(of: "img-src 'self'", with: "img-src 'self' stbu-app:")
                bytes = Data(html.utf8)
            case "js": mime = "text/javascript"
            case "css": mime = "text/css"
            default: mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            }
            task.didReceive(URLResponse(url: url, mimeType: mime, expectedContentLength: bytes.count, textEncodingName: "utf-8"))
            task.didReceive(bytes)
            task.didFinish()
        } catch { task.didFailWithError(error) }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}
