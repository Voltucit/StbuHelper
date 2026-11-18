import CryptoKit
import Foundation

@MainActor
final class Wallet {
    private let http: OfficialHTTP
    private let bucket = "quzhi.wallet"
    private let base = "https://v3-api.china-qzxy.cn"
    private let client = ["phoneSystem": "ios", "version": "6.5.28"]

    init(http: OfficialHTTP) { self.http = http }

    func status() -> Object {
        let connected = ((try? Keychain.read(bucket, as: [String: String].self)) != nil)
        return ["connected": connected, "devices": [Object](), "waterControl": false]
    }

    func login(_ args: Object) async throws -> Object {
        let phone = args.text("telephone"), password = args.text("password")
        guard phone.range(of: #"^1[0-9]{10}$"#, options: .regularExpression) != nil,
              !password.isEmpty, password.count <= 1024 else { throw AppError("请检查手机号和密码") }
        // Vendor login uses a legacy MD5 suffix over HTTPS. Local session storage uses Keychain.
        let digest = Insecure.MD5.hash(data: Data(password.utf8)).map { String(format: "%02x", $0) }.joined()
        let result = try await request("/user/login", fields: client.merging([
            "telephone": phone, "password": digest.suffix(10).uppercased(), "type": "0"
        ]) { _, new in new }, post: true)
        guard let user = result as? Object, let account = user["userAccount"] as? Object else { throw AppError("登录响应缺少账号信息") }
        let session = ["telephone": phone, "telPhone": phone, "loginCode": user.text("loginCode"),
                       "userId": user.text("userId"), "accountId": account.text("accountId"), "projectId": account.text("projectId")]
        guard !session.values.contains(where: \.isEmpty) else { throw AppError("未取得完整登录会话") }
        try Keychain.write(bucket, session)
        return status()
    }

    func logout() throws -> Object { try Keychain.remove(bucket); return status() }

    func balance() async throws -> Object {
        let result = try await request("/account/wallet", fields: auth())
        let money = (result as? Object)?.text("money") ?? ""
        let amount: Any = Decimal(string: money, locale: Locale(identifier: "en_US_POSIX")) == nil ? NSNull() as Any : money
        return ["wallet": ["money": amount], "updatedAt": ISO8601DateFormatter().string(from: Date())]
    }

    func bills(_ month: String) async throws -> Object {
        guard month.range(of: #"^[0-9]{4}-(0[1-9]|1[0-2])$"#, options: .regularExpression) != nil else { throw AppError("请选择有效月份") }
        let result = try await request("/order/query/account/bill/list", fields: auth().merging(["month": month, "billRequestType": "2"]) { _, new in new })
        let object = result as? Object
        guard let rows = result as? [Object] ?? object?["list"] as? [Object] ?? object?["records"] as? [Object] else {
            throw AppError("账单格式暂未识别")
        }
        var incomplete = 0
        var seen = Set<String>()
        let bills = rows.compactMap { wrapper -> Object? in
            let row = wrapper["consumeBillDTO"] as? Object ?? wrapper
            let money = row.text("consumeMoney"), date = row.text("consumeDate", row.text("createTime"))
            guard let amount = Decimal(string: money, locale: Locale(identifier: "en_US_POSIX")), !date.isEmpty,
                  amount != 0 || row["uploadDate"] == nil || !row.text("uploadDate").isEmpty else { incomplete += 1; return nil }
            let description = row.text("description", row.text("deviceName", row.text("bigTypeName", "用水消费")))
            let order = row.text("orderNo")
            guard seen.insert(row.text("orderId", order.isEmpty ? description + date + money : order)).inserted else { return nil }
            return ["name": description, "date": date, "money": money, "orderNo": order]
        }
        return ["bills": bills, "incomplete": incomplete]
    }

    private func auth() throws -> [String: String] {
        guard let session = try Keychain.read(bucket, as: [String: String].self) else { throw AppError("请先连接趣智校园") }
        return client.merging(session) { _, new in new }
    }

    private func request(_ path: String, fields: [String: String], post: Bool = false) async throws -> Any {
        let encoded = OfficialHTTP.form(fields)
        var request = URLRequest(url: URL(string: base + path + (post ? "" : "?" + encoded))!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if post {
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(encoded.utf8)
        }
        let (data, response) = try await http.send(request)
        guard (200...299).contains(response.statusCode),
              let result = try JSONSerialization.jsonObject(with: data) as? Object else { throw AppError("趣智校园暂未返回数据") }
        guard result["success"] as? Bool == true, result.number("errorCode") == 0 else {
            throw AppError(String(result.text("errorMessage", "趣智校园未完成此操作").prefix(180)))
        }
        return result["data"] ?? NSNull()
    }
}
