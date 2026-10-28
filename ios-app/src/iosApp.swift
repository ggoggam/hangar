import KotlinModules
import SwiftUI

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct HangarApp: App {
    init() {
        // Before launching finishes, which is when iOS wants the background task registered.
        MainViewControllerKt.startHangar(store: KeychainBridge(), signIn: SignInBridge())
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.all)
        }
    }
}
