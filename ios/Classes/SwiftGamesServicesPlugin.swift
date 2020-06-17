import Flutter
import UIKit
import GameKit

public class SwiftGamesServicesPlugin: NSObject, FlutterPlugin {
    var alreadyAuthenticated: Bool = false

    // MARK: - Properties

    var viewController: UIViewController {
        return UIApplication.shared.keyWindow!.rootViewController!
    }

    // MARK: - Authenticate

    func authenticateUser(result: @escaping FlutterResult) {
        let player = GKLocalPlayer.local

        if self.alreadyAuthenticated {
            result("success")
            return
        }

        player.authenticateHandler = { vc, error in
            self.alreadyAuthenticated = true

            guard error == nil else {
                result(FlutterError( code: "authError",
                                     message: error?.localizedDescription ?? "",
                                     details: "" ))
                return
            }
            if let vc = vc {
                self.viewController.present(vc, animated: true, completion: nil)
            } else if player.isAuthenticated {
                result("success")
            } else {
                result(FlutterError( code: "error",
                                     message: "Unknown authenticateHandler error",
                                     details: "" ))
            }
        }
    }

    // MARK: - Leaderboard

    func showLeaderboardWith(identifier: String) {
        let vc = GKGameCenterViewController()
        vc.gameCenterDelegate = self
        vc.viewState = .achievements
        vc.leaderboardIdentifier = identifier
        viewController.present(vc, animated: true, completion: nil)
    }

    func report(score: Int64, leaderboardID: String, result: @escaping FlutterResult) {
        let reportedScore = GKScore(leaderboardIdentifier: leaderboardID)
        reportedScore.value = score
        GKScore.report([reportedScore]) { (error) in
            guard error == nil else {
                result(error?.localizedDescription ?? "")
                return
            }
            result("success")
        }
    }

    // MARK: - Achievements

    func showAchievements() {
        let vc = GKGameCenterViewController()
        vc.gameCenterDelegate = self
        vc.viewState = .achievements
        viewController.present(vc, animated: true, completion: nil)
    }

    func report(achievementID: String, percentComplete: Double, result: @escaping FlutterResult) {
        let achievement = GKAchievement(identifier: achievementID)
        achievement.percentComplete = percentComplete
        achievement.showsCompletionBanner = true
        GKAchievement.report([achievement]) { (error) in
            guard error == nil else {
                result(error?.localizedDescription ?? "")
                return
            }
            result("success")
        }
    }

    // MARK: - Data Storage

    func save(json: String, result: @escaping FlutterResult) {
        let player = GKLocalPlayer.local
        guard let data = json.data(using: .utf8) else {
            result(FlutterError(code: "saveStringEncoding", message: "Utf8 encoding failed", details: nil))
            return
        }
        if !player.isAuthenticated {
            result(FlutterError(code: "playerNotAuthenticated", message: "Local player is not authenticated", details: nil))
            return
        }
        player.saveGameData(data, withName: "flutter.data") { (savedGameData, error) in
            guard nil == error else {
                result(FlutterError(code: "saveError", message: error?.localizedDescription ?? "", details: nil))
                return
            }
            result("success")
        }
    }

    func load(result: @escaping FlutterResult) {
        let player = GKLocalPlayer.local
        if !player.isAuthenticated {
            result(FlutterError(code: "playerNotAuthenticated", message: "Local player is not authenticated", details: nil))
            return
        }
        player.fetchSavedGames { (savedGames, error) in
            guard nil == error, let savedGames = savedGames else {
                result(FlutterError(code: "loadSavedData", message: error?.localizedDescription ?? "load game service error", details: nil))
                return
            }
            // Order by save date decending
            let save = savedGames.sorted(by: { $0.modificationDate?.compare($1.modificationDate ?? $0.modificationDate!) == .orderedDescending })
            // List all saved games with same name
            let games = save.filter({$0.name == "flutter.data"})
            // if we have a saved game
            if let saveGame = save.first(where: {$0.name == "flutter.data" }) {
                // load most recent saved data
                saveGame.loadData { (data, error) in
                    guard let data = data, nil == error else {
                        result(FlutterError(code: "loadSavedData", message: error?.localizedDescription ?? "load game data error", details: nil))
                        return
                    }
                    if games.count > 1 {
                        // Resolve conflicts with last data
                        player.resolveConflictingSavedGames(games, with: data, completionHandler: nil)
                    }
                    // return last data
                    result(String.init(data: data, encoding: .utf8));
                };
            }
        }
    }

    // MARK: - FlutterPlugin

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? [String: Any]
        switch call.method {
        case "unlock":
            let achievementID = (arguments?["achievementID"] as? String) ?? ""
            let percentComplete = (arguments?["percentComplete"] as? Double) ?? 0.0
            report(achievementID: achievementID, percentComplete: percentComplete, result: result)
        case "submitScore":
            let leaderboardID = (arguments?["leaderboardID"] as? String) ?? ""
            let score = (arguments?["value"] as? Int) ?? 0
            report(score: Int64(score), leaderboardID: leaderboardID, result: result)
        case "showAchievements":
            showAchievements()
            result("success")
        case "showLeaderboards":
            let leaderboardID = (arguments?["iOSLeaderboardID"] as? String) ?? ""
            showLeaderboardWith(identifier: leaderboardID)
            result("success")
        case "signIn":
            authenticateUser(result: result)
        case "saveData":
            let data = (arguments?["data"] as? String) ?? ""
            save(json: data, result: result)
        case "loadData":
            load(result: result)
        default:
            result("unimplemented")
            break
        }
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "games_services", binaryMessenger: registrar.messenger())
        let instance = SwiftGamesServicesPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
}

// MARK: - GKGameCenterControllerDelegate

extension SwiftGamesServicesPlugin: GKGameCenterControllerDelegate {

    public func gameCenterViewControllerDidFinish(_ gameCenterViewController: GKGameCenterViewController) {
        viewController.dismiss(animated: true, completion: nil)
    }
}
