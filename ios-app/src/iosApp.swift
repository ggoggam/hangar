import KotlinModules
import SwiftUI

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(store: KeychainBridge(), signIn: SignInBridge())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct HangarApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.all)
        }
    }
}
