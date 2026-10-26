import AuthenticationServices
import KotlinModules
import UIKit

/// Fly.io's approval page in ASWebAuthenticationSession for Kotlin's `IosSignInBridge`. It shares
/// Safari's cookies, so a user already signed in to fly.io only has to approve. Fly.io's page has
/// no redirect to come back on: the user copies the code it shows and closes the sheet, which ends
/// the session with `canceledLogin`, the normal way out here.
final class SignInBridge: NSObject, IosSignInBridge, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func open(url: String) {
        guard let target = URL(string: url) else { return }
        DispatchQueue.main.async {
            self.session?.cancel()
            let session = ASWebAuthenticationSession(url: target, callbackURLScheme: nil) { [weak self] _, _ in
                self?.session = nil
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            session.start()
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
    }
}
