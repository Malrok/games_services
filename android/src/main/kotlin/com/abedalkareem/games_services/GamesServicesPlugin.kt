package com.abedalkareem.games_services

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.drive.Drive
import com.google.android.gms.games.AchievementsClient
import com.google.android.gms.games.Games
import com.google.android.gms.games.LeaderboardsClient
import com.google.android.gms.games.SnapshotsClient
import com.google.android.gms.games.SnapshotsClient.DataOrConflict
import com.google.android.gms.games.snapshot.Snapshot
import com.google.android.gms.games.snapshot.SnapshotMetadataChange
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import java.io.IOException


private const val CHANNEL_NAME = "games_services"
private const val RC_SIGN_IN = 9000
private const val SAVE_FILENAME = "data_save_filename"
private const val SAVE_DESCRIPTION = "data_save_description"

class GamesServicesPlugin(private var activity: Activity? = null) : FlutterPlugin, MethodCallHandler, ActivityAware, ActivityResultListener {

  //region Variables
  private var googleSignInClient: GoogleSignInClient? = null
  private var achievementClient: AchievementsClient? = null
  private var leaderboardsClient: LeaderboardsClient? = null
  private var activityPluginBinding: ActivityPluginBinding? = null
  private var channel: MethodChannel? = null
  private var pendingOperation: PendingOperation? = null
  //endregion

  companion object {
    @JvmStatic
    fun registerWith(registrar: PluginRegistry.Registrar) {
      val channel = MethodChannel(registrar.messenger(), CHANNEL_NAME)
      val plugin = GamesServicesPlugin(registrar.activity())
      channel.setMethodCallHandler(plugin)
      registrar.addActivityResultListener(plugin)
    }
  }

  //region SignIn
  private fun silentSignIn(result: Result) {
    val activity = activity ?: return
    val builder = GoogleSignInOptions.Builder(
            GoogleSignInOptions.DEFAULT_GAMES_SIGN_IN)
            .requestScopes(Drive.SCOPE_APPFOLDER)
    googleSignInClient = GoogleSignIn.getClient(activity, builder.build())
    googleSignInClient?.silentSignIn()?.addOnCompleteListener { task ->
      pendingOperation = PendingOperation(Methods.silentSignIn, result)
      if (task.isSuccessful) {
        val googleSignInAccount = task.result
        handleSignInResult(googleSignInAccount!!)
      } else {
        Log.e("Error", "signInError", task.exception)
        Log.i("ExplicitSignIn", "Trying explicit sign in")
        explicitSignIn()
      }
    }
  }

  private fun explicitSignIn() {
    val activity = activity ?: return
    val builder = GoogleSignInOptions.Builder(
            GoogleSignInOptions.DEFAULT_GAMES_SIGN_IN)
            .requestEmail()
    googleSignInClient = GoogleSignIn.getClient(activity, builder.build())
    activity.startActivityForResult(googleSignInClient?.signInIntent, RC_SIGN_IN)
  }

  private fun handleSignInResult(googleSignInAccount: GoogleSignInAccount) {
    achievementClient = Games.getAchievementsClient(activity!!, googleSignInAccount)
    leaderboardsClient = Games.getLeaderboardsClient(activity!!, googleSignInAccount)
    finishPendingOperationWithSuccess()
  }
  //endregion

  //region Achievements & Leaderboards
  private fun showAchievements(result: Result) {
    showLoginErrorIfNotLoggedIn(result)
    achievementClient!!.achievementsIntent.addOnSuccessListener { intent ->
      activity?.startActivityForResult(intent, 0)
      result.success("success")
    }.addOnFailureListener {
      result.error("error", "${it.message}", null)
    }
  }

  private fun unlock(achievementID: String, result: Result) {
    showLoginErrorIfNotLoggedIn(result)
    achievementClient?.unlockImmediate(achievementID)
            ?.addOnSuccessListener {
              result.success("success")
            }?.addOnFailureListener {
              result.error("error", it.localizedMessage, null)
            }
  }

  private fun showLeaderboards(result: Result) {
    showLoginErrorIfNotLoggedIn(result)
    leaderboardsClient!!.allLeaderboardsIntent.addOnSuccessListener { intent ->
      activity?.startActivityForResult(intent, 0)
      result.success("success")
    }.addOnFailureListener {
      result.error("error", "${it.message}", null)
    }
  }

  private fun submitScore(leaderboardID: String, score: Int, result: Result) {
    showLoginErrorIfNotLoggedIn(result)
    leaderboardsClient
            ?.submitScoreImmediate(leaderboardID, score.toLong())
            ?.addOnSuccessListener {
              result.success("success")
            }?.addOnFailureListener {
              result.error("error", it.localizedMessage, null)
            }
  }

  private fun showLoginErrorIfNotLoggedIn(result: Result) {
    if (achievementClient == null || leaderboardsClient == null) {
      result.error("error", "Please make sure to call signIn() first", null)
    }
  }
  //endregion

  //region save/load data
  private fun saveData(data: String, result: Result) {
    if (achievementClient == null || leaderboardsClient == null) {
      result.success("could not save as we're not logged in");
    }
    val snapshotsClient = Games.getSnapshotsClient(activity!!, GoogleSignIn.getLastSignedInAccount(activity)!!)

    val task = snapshotsClient.open(SAVE_FILENAME, true, SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED)
    task.addOnSuccessListener { dataOrConflict ->
      this.processSnapshotOpenResult(data.toByteArray(), result, dataOrConflict, snapshotsClient)
    }.addOnFailureListener {
      result.error("error", it.localizedMessage, null)
    }
  }

  private fun processSnapshotOpenResult(data: ByteArray, result: Result, dataOrConflict: DataOrConflict<Snapshot>,
                                         snapshotsClient: SnapshotsClient)
  {
    if (dataOrConflict.isConflict) {
      result.error("error", "unresolved conflict found in game saves", null)
    } else {
      val snapshotContents = dataOrConflict.data?.snapshotContents
      val snapshot = dataOrConflict.data
      if (snapshotContents != null) {
        snapshotContents.writeBytes(data)
        val metadataChange = SnapshotMetadataChange.Builder()
                .setDescription(SAVE_DESCRIPTION)
                .build()

        // Commit the operation
        snapshotsClient.commitAndClose(snapshot!!, metadataChange)
                .addOnSuccessListener {
                  result.success("success")
                }
                .addOnFailureListener { exception ->
                  result.error("error", exception.localizedMessage, null)
                }
        val source: TaskCompletionSource<Snapshot?> = TaskCompletionSource()
        source.setResult(snapshot)
      } else {
        result.error("error", "could not get a SnapshotContent object", null)
      }
    }
  }

  private fun loadData(result: Result) {
    if (achievementClient == null || leaderboardsClient == null) {
      result.success("could not load as we're not logged in");
    }
    val snapshotsClient = Games.getSnapshotsClient(activity!!, GoogleSignIn.getLastSignedInAccount(activity)!!)

    val task = snapshotsClient.open(SAVE_FILENAME, true, SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED)
    task.addOnSuccessListener { dataOrConflict ->
      dataOrConflict.apply {
        val snapshotContents = this.data?.snapshotContents
        if (snapshotContents != null) {
          try {
            val bytes = snapshotContents.readFully()
            result.success(String(bytes))
          } catch (error: IOException) {
            result.error("error", "exception raised while reading data", null)
          }
        } else {
          result.error("error", "could not get a SnapshotContent object", null)
        }
      }
    }.addOnFailureListener {
      result.error("error", it.localizedMessage, null)
    }
  }
  //endregion

  //region FlutterPlugin
  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    setupChannel(binding.binaryMessenger)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    teardownChannel()
  }

  private fun setupChannel(messenger: BinaryMessenger) {
    channel = MethodChannel(messenger, CHANNEL_NAME)
    channel?.setMethodCallHandler(this)
  }

  private fun teardownChannel() {
    channel?.setMethodCallHandler(null)
    channel = null
  }
  //endregion

  //region ActivityAware

  private fun disposeActivity() {
    activityPluginBinding?.removeActivityResultListener(this)
    activityPluginBinding = null
  }

  override fun onDetachedFromActivity() {
    disposeActivity()
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activityPluginBinding = binding
    activity = binding.activity
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity()
  }

  //endregion

  //region PendingOperation
  private class PendingOperation constructor(val method: String, val result: Result)

  private fun finishPendingOperationWithSuccess() {
    Log.i(pendingOperation!!.method, "success")
    pendingOperation!!.result.success("success")
    pendingOperation = null
  }

  private fun finishPendingOperationWithError(errorMessage: String) {
    Log.i(pendingOperation!!.method, "error")
    pendingOperation!!.result.error("error", errorMessage, null)
    pendingOperation = null
  }
  //endregion

  //region ActivityResultListener
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode == RC_SIGN_IN) {
      val result = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
      val signInAccount = result.signInAccount
      if (result.isSuccess && signInAccount != null) {
        handleSignInResult(signInAccount)
      } else {
        var message = result.status.statusMessage ?: ""
        if (message.isEmpty()) {
          message = "Something went wrong " + result.status
        }
        finishPendingOperationWithError(message)
      }
      return true
    }
    return false
  }
  //endregion

  //region MethodCallHandler
  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      Methods.unlock -> {
        unlock(call.argument<String>("achievementID") ?: "", result)
      }
      Methods.submitScore -> {
        val leaderboardID = call.argument<String>("leaderboardID") ?: ""
        val score = call.argument<Int>("value") ?: 0
        submitScore(leaderboardID, score, result)
      }
      Methods.showLeaderboards -> {
        showLeaderboards(result)
      }
      Methods.showAchievements -> {
        showAchievements(result)
      }
      Methods.silentSignIn -> {
        silentSignIn(result)
      }
      Methods.saveData -> {
        val data = call.argument<String>("data") ?: ""
        saveData(data, result)
      }
      Methods.loadData -> {
        loadData(result)
      }
      else -> result.notImplemented()
    }
  }
  //endregion
}

object Methods {
  const val unlock = "unlock"
  const val submitScore = "submitScore"
  const val showLeaderboards = "showLeaderboards"
  const val showAchievements = "showAchievements"
  const val silentSignIn = "silentSignIn"
  const val saveData = "saveData"
  const val loadData = "loadData"
}