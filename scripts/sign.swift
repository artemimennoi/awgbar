// Ed25519 signatures for release checksums; the macOS app's updater checks them with the public
// key it carries (Updater.swift), so a release only installs if it was signed with this key.
//
//   swift scripts/sign.swift keygen <key file>        new key (0600), prints the public key
//   swift scripts/sign.swift sign <key file> <file>   prints the signature of <file>, base64
//   swift scripts/sign.swift public <key file>        prints the public key, base64
//   swift scripts/sign.swift verify <public key> <file> <signature file>
//                                                     checks the signature as the updater does
//
// The key file holds the raw private key in base64. Keep it outside the repository and back it
// up: without it no update can be signed, and a new key needs an app release with the new public key.
import CryptoKit
import Foundation

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("sign.swift: \(message)\n".utf8))
    exit(1)
}

func loadKey(_ path: String) -> Curve25519.Signing.PrivateKey {
    guard let text = try? String(contentsOfFile: path, encoding: .utf8),
          let raw = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)),
          let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw) else {
        fail("cannot read the key from \(path)")
    }
    return key
}

let args = CommandLine.arguments.dropFirst()
switch (args.first, args.count) {
case ("keygen", 2):
    let path = args[args.startIndex + 1]
    guard !FileManager.default.fileExists(atPath: path) else { fail("\(path) exists; not overwriting it") }
    let key = Curve25519.Signing.PrivateKey()
    try? FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent,
                                             withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    guard FileManager.default.createFile(atPath: path, contents: Data((key.rawRepresentation.base64EncodedString() + "\n").utf8),
                                         attributes: [.posixPermissions: 0o600]) else { fail("cannot write \(path)") }
    print(key.publicKey.rawRepresentation.base64EncodedString())
case ("sign", 3):
    let key = loadKey(args[args.startIndex + 1])
    guard let data = FileManager.default.contents(atPath: args[args.startIndex + 2]) else { fail("cannot read \(args[args.startIndex + 2])") }
    guard let signature = try? key.signature(for: data) else { fail("signing failed") }
    print(signature.base64EncodedString())
case ("public", 2):
    print(loadKey(args[args.startIndex + 1]).publicKey.rawRepresentation.base64EncodedString())
case ("verify", 4):
    let (publicKey, path, signaturePath) = (args[args.startIndex + 1], args[args.startIndex + 2], args[args.startIndex + 3])
    guard let raw = Data(base64Encoded: publicKey), let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw) else {
        fail("not a public key: \(publicKey)")
    }
    guard let data = FileManager.default.contents(atPath: path) else { fail("cannot read \(path)") }
    guard let text = try? String(contentsOfFile: signaturePath, encoding: .utf8),
          let signature = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
        fail("cannot read the signature from \(signaturePath)")
    }
    guard key.isValidSignature(signature, for: data) else { fail("\(signaturePath) is not a valid signature of \(path)") }
    print("\(path): good signature")
default:
    fail("usage: sign.swift keygen <key> | sign <key> <file> | public <key> | verify <public key> <file> <signature>")
}
