package dev.patrickgold.florisboard.app.settings.account

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.settings.repli.RepliAction
import dev.patrickgold.florisboard.app.settings.repli.RepliCard
import dev.patrickgold.florisboard.app.settings.repli.RepliLabel
import dev.patrickgold.florisboard.app.settings.repli.RepliStyle
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.repli.openai.auth.ChatGptAccountManager
import dev.patrickgold.florisboard.repli.account.RepliWelcomePreferences
import kotlinx.coroutines.launch

private const val LAST_STEP = 5

@Composable
fun RepliOnboardingScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val navController = LocalNavController.current
    val account by ChatGptAccountManager.state.collectAsState()
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { ChatGptAccountManager.completePendingSignIn() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val selected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
    var step by rememberSaveable { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    LaunchedEffect(step) { scrollState.scrollTo(0) }

    fun finish() {
        RepliWelcomePreferences.complete(context)
        if (selected) scope.launch { prefs.internal.isImeSetUp.set(true) }
        navController.navigate(Routes.Settings.RepliHome) {
            popUpTo(Routes.Settings.RepliWelcome) { inclusive = true }
            launchSingleTop = true
        }
    }

    BackHandler(step > 0) { step-- }
    val paper = RepliStyle.paper
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect {
        activity.window.statusBarColor = paper.toArgb()
        activity.window.navigationBarColor = paper.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    Column(Modifier.fillMaxSize().background(paper).statusBarsPadding().padding(horizontal = 24.dp)) {
        if (step > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$step / $LAST_STEP", color = RepliStyle.muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                repeat(LAST_STEP) { index ->
                    Box(Modifier.padding(start = 5.dp).size(width = 27.dp, height = 3.dp)
                        .background(if (index + 1 == step) RepliStyle.accent else RepliStyle.line,
                            RoundedCornerShape(2.dp)))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
            .padding(top = if (step == 0) 0.dp else 22.dp, bottom = 18.dp)) {
            when (step) {
                0 -> {
                    Spacer(Modifier.height(52.dp))
                    RepliBrandMark()
                    Spacer(Modifier.height(58.dp))
                    Text("Sound like you,\nevery time.", color = RepliStyle.ink,
                        fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    RepliLabel("Thoughtful replies for the conversations that matter. You stay in control of every word.",
                        17, RepliStyle.muted)
                    Spacer(Modifier.height(50.dp))
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
                        color = RepliStyle.card) {
                        Column(Modifier.padding(20.dp)) {
                            RepliLabel("Them", 13, RepliStyle.muted, bold = true)
                            Spacer(Modifier.height(8.dp))
                            RepliLabel("Would Saturday work for you?", 17, RepliStyle.ink, bold = true)
                            Spacer(Modifier.height(18.dp))
                            Surface(Modifier.fillMaxWidth(0.78f).align(Alignment.End),
                                shape = RoundedCornerShape(50), color = RepliStyle.accentSoft) {
                                Text("Saturday might be busy...", color = RepliStyle.accent,
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                    maxLines = 1)
                            }
                        }
                    }
                }
                1 -> {
                    OnboardingTitle("Bring the chat. Keep the context.")
                    Spacer(Modifier.height(12.dp))
                    RepliLabel("Capture more than one view, then check what Repli read before you ask for ideas.",
                        16, RepliStyle.muted)
                    Spacer(Modifier.height(32.dp))
                    RepliCard(tinted = true) {
                        RepliLabel("Alex", 14, RepliStyle.ink, bold = true)
                        Spacer(Modifier.height(18.dp))
                        SampleMessage("You", "That little café looks great.", true)
                        Spacer(Modifier.height(10.dp))
                        SampleMessage("Them", "Would Saturday work for you?", false)
                        Spacer(Modifier.height(18.dp))
                        RepliLabel("+ Capture another view", 13, RepliStyle.accent, bold = true)
                    }
                    Spacer(Modifier.height(16.dp))
                    OnboardingNote("Capture another page", "Add earlier messages when one screen isn’t enough.")
                }
                2 -> {
                    OnboardingTitle("Review chats before Repli replies.")
                    Spacer(Modifier.height(12.dp))
                    RepliLabel("Check the chat name, speakers, and messages. Correct or remove anything Repli got wrong.",
                        16, RepliStyle.muted)
                    Spacer(Modifier.height(30.dp))
                    RepliCard {
                        RepliLabel("Alex · practice chat", 13, RepliStyle.accent, bold = true)
                        Spacer(Modifier.height(18.dp))
                        SampleMessage("You", "That little café looks great.", true)
                        Spacer(Modifier.height(10.dp))
                        SampleMessage("Them", "Would Saturday work for you?", false)
                    }
                    Spacer(Modifier.height(16.dp))
                    OnboardingNote("How do you want to Repli?", "Add a point or direction before generating, if you want.")
                }
                3 -> {
                    OnboardingTitle("Make every reply your kind of reply.")
                    Spacer(Modifier.height(12.dp))
                    RepliLabel("One message, two real personas. Choose how your reply should sound.",
                        16, RepliStyle.muted)
                    Spacer(Modifier.height(28.dp))
                    SampleMessage("Alex asks", "Would Saturday work for you?", false)
                    Spacer(Modifier.height(20.dp))
                    PersonaExample("Easy Breezy", "Saturday’s a bit packed, but I’m free later in the week 🙂")
                    Spacer(Modifier.height(12.dp))
                    PersonaExample("The Soft Spot", "I’d love to see you. Saturday may be tricky, but I can make time later in the week 💜")
                    Spacer(Modifier.height(16.dp))
                    RepliLabel("Tap a suggestion to insert it, then edit before sending.", 13, RepliStyle.muted)
                }
                4 -> {
                    OnboardingTitle("Make Repli your keyboard.")
                    Spacer(Modifier.height(12.dp))
                    RepliLabel("Android needs you to enable and select Repli. We’ll guide you through both steps.",
                        16, RepliStyle.muted)
                    Spacer(Modifier.height(30.dp))
                    RepliCard {
                        RepliLabel(if (enabled) "✓  Repli keyboard enabled" else "1  Enable Repli keyboard",
                            17, RepliStyle.ink, bold = true)
                        Spacer(Modifier.height(5.dp))
                        RepliLabel("Open Android keyboard settings", 13, RepliStyle.muted)
                        if (!enabled) {
                            Spacer(Modifier.height(12.dp))
                            RepliAction("Open keyboard settings", {
                                InputMethodUtils.showImeEnablerActivity(context)
                            }, filled = false)
                        }
                        Spacer(Modifier.height(22.dp))
                        RepliLabel(if (selected) "✓  Repli keyboard selected" else "2  Select Repli keyboard",
                            17, RepliStyle.ink, bold = true)
                        Spacer(Modifier.height(5.dp))
                        RepliLabel("Choose it as your input method", 13, RepliStyle.muted)
                        if (enabled && !selected) {
                            Spacer(Modifier.height(12.dp))
                            RepliAction("Choose keyboard", { InputMethodUtils.showImePicker(context) },
                                filled = false)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    OnboardingNote("Always your choice", "Edit a reply before inserting it. Repli never sends it for you.")
                }
                else -> {
                    Spacer(Modifier.height(20.dp))
                    RepliBrandMark()
                    Spacer(Modifier.height(38.dp))
                    OnboardingTitle("Make it yours.")
                    Spacer(Modifier.height(12.dp))
                    RepliLabel("Repli is open source and has no servers. AI replies run on your own ChatGPT Plus or Pro plan — nothing is billed by Repli.",
                        16, RepliStyle.muted)
                    Spacer(Modifier.height(32.dp))
                    when {
                        account.planAllowed -> OnboardingNote("Using ChatGPT plan", "Signed in as ${account.email ?: "your ChatGPT account"}. You’re ready to use Repli.")
                        account.finishPending -> RepliAction(if (account.busy) "Finishing…" else "Finish signing in", {
                            scope.launch { ChatGptAccountManager.completePendingSignIn() }
                        }, filled = false, enabled = !account.busy)
                        else -> RepliAction(if (account.busy) "Waiting for ChatGPT…" else "Continue with ChatGPT", {
                            scope.launch { ChatGptAccountManager.signIn(context) }
                        }, filled = false, enabled = !account.busy)
                    }
                    account.errorMessage?.let {
                        Spacer(Modifier.height(10.dp))
                        RepliLabel(it, 13, RepliStyle.ink)
                    }
                    Spacer(Modifier.height(16.dp))
                    RepliLabel("No ChatGPT plan? You can add your own OpenAI API key later in Repli settings. Typing and suggestions work on your phone without either.",
                        13, RepliStyle.muted)
                }
            }
        }
        if (step == 0) {
            RepliAction("Get started", { step = 1 })
        } else {
            RepliAction(if (step == LAST_STEP) {
                if (account.planAllowed) "Finish setup" else "Skip for now"
            } else "Continue", {
                if (step == LAST_STEP) finish() else step++
            })
            if (step == 4 && !selected) {
                Text("You can enable the keyboard later in Settings", color = RepliStyle.muted,
                    fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RepliBrandMark() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_repli_launcher), null,
            Modifier.size(108.dp), tint = Color.Unspecified)
        Spacer(Modifier.height(12.dp))
        Text("repli", color = RepliStyle.ink, fontSize = 48.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OnboardingTitle(text: String) {
    Text(text, color = RepliStyle.ink, fontSize = 30.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.Bold)
}

@Composable
private fun SampleMessage(speaker: String, message: String, fromMe: Boolean) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = if (fromMe) RepliStyle.accentSoft else RepliStyle.card,
        border = BorderStroke(1.dp, RepliStyle.line)) {
        Column(Modifier.padding(14.dp)) {
            RepliLabel(speaker, 12, RepliStyle.accent, bold = true)
            Spacer(Modifier.height(4.dp))
            RepliLabel(message, 15, RepliStyle.ink)
        }
    }
}

@Composable
private fun PersonaExample(name: String, reply: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = RepliStyle.card, border = BorderStroke(1.dp, RepliStyle.line)) {
        Column(Modifier.padding(16.dp)) {
            RepliLabel(name, 14, RepliStyle.accent, bold = true)
            Spacer(Modifier.height(8.dp))
            RepliLabel(reply, 16, RepliStyle.ink)
        }
    }
}

@Composable
private fun OnboardingNote(title: String, detail: String) {
    RepliCard(tinted = true) {
        RepliLabel(title, 15, RepliStyle.accent, bold = true)
        Spacer(Modifier.height(4.dp))
        RepliLabel(detail, 14, RepliStyle.ink)
    }
}
