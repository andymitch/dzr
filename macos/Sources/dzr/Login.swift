import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.signedIn {
            MainView().task { await model.refreshLiked() }
        } else {
            LoginView()
        }
    }
}

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @State private var code: LoginCode?
    @State private var error: String?

    var body: some View {
        VStack(spacing: 24) {
            Image(nsImage: .pixelHeart).resizable().interpolation(.none).frame(width: 56, height: 48).foregroundStyle(Color.accent)
            VStack(spacing: 6) {
                Text("Sign in to Deezer").font(.largeTitle.bold())
                Text("Approve this device from your phone or any browser signed in to Deezer.").foregroundStyle(.secondary)
            }
            if let code {
                HStack(spacing: 32) {
                    if let qr = qrImage(code.url.absoluteString) {
                        Image(nsImage: qr).interpolation(.none).resizable().frame(width: 160, height: 160)
                            .padding(14).background(.white, in: .rect(cornerRadius: 20))
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Or go to dzr.fm/tv and enter").foregroundStyle(.secondary)
                        Text(code.code.uppercased()).font(.system(size: 44, weight: .heavy, design: .rounded)).kerning(6).foregroundStyle(Color.accent)
                        Link(destination: code.url) { Label("Open in browser", systemImage: "safari") }
                            .buttonStyle(.glass)
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.mini)
                            Text("Waiting for approval").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(28)
                .background(.regularMaterial, in: .rect(cornerRadius: 28))
            } else if let error {
                Text(error).foregroundStyle(.red)
                Button("Try Again") { self.error = nil }.buttonStyle(.glassProminent)
            } else {
                ProgressView()
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            LinearGradient(colors: [Color.accent.opacity(0.25), .clear], startPoint: .top, endPoint: .center)
                .ignoresSafeArea()
        )
        .task(id: error) {
            guard error == nil else { return }
            do {
                let c = try await Deezer.startLogin()
                code = c
                model.signedIn(try await Deezer.pollLogin(c.code))
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
                code = nil
            }
        }
    }

    private func qrImage(_ s: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        guard let ci = f.outputImage else { return nil }
        let rep = NSCIImageRep(ciImage: ci)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
