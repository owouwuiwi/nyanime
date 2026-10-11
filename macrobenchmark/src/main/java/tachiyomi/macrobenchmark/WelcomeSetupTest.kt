package tachiyomi.macrobenchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import java.util.regex.Pattern

/** Clear only the isolated benchmark package; never reset the user's installation. */
class WelcomeSetupTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun label(
        english: String,
        italian: String,
    ) = By.text(Pattern.compile("${Pattern.quote(english)}|${Pattern.quote(italian)}"))
    private val continueButton = label("Continue", "Continua")
    private val start = label("Start Nyanime", "Entra in Nyanime")
    private val local = label("Restore a backup file", "Ripristina un file backup")
    private val finalTitle = label("Start fresh or pick up where you left off", "Inizia da zero o riprendi da dove eri")

    private fun reset(fixture: String = "") {
        check(TARGET_PACKAGE.endsWith(".benchmark"))
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        check(device.executeShellCommand("pm clear $TARGET_PACKAGE").contains("Success"))
        val activity = if (fixture.isEmpty()) "eu.kanade.tachiyomi.ui.main.MainActivity" else FIXTURE_ACTIVITY
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$activity $fixture")
    }

    private fun next(title: androidx.test.uiautomator.BySelector) {
        clickFresh(device, continueButton)
        check(device.waitForFresh(title, 15_000))
    }

    private fun finishWelcome() {
        check(device.waitForFresh(label("Your space is ready", "Il tuo spazio è pronto"), 15_000))
        next(label("Make it yours", "Scegli il tuo stile"))
        next(label("You decide what to allow", "Decidi tu cosa consentire"))
        next(label("Choose your updates", "Scegli gli aggiornamenti"))
        next(finalTitle)
        clickFresh(device, start)
    }

    private fun openAdvancedSettings() {
        check(device.waitForFresh(By.text("Home"), 30_000))
        clickFresh(device, label("More", "Altro"))
        val settings = label("Settings", "Impostazioni")
        scrollTo(settings)
        clickFresh(device, settings)
        val advanced = label("Advanced", "Avanzate")
        scrollTo(advanced)
        clickFresh(device, advanced)
    }

    private fun scrollTo(selector: androidx.test.uiautomator.BySelector) {
        check(device.waitForFresh(By.scrollable(true), 10_000))
        repeat(8) {
            if (device.waitForFresh(selector, 100)) return
            device.findObject(By.scrollable(true)).scroll(Direction.DOWN, 0.7f)
        }
        check(device.waitForFresh(selector, 10_000))
    }

    @Test fun settingsReviewCompletesAndBackReturnsWithoutRepeatingInitialSetup() {
        reset()
        check(device.waitForFresh(label("Welcome to Nyanime", "Benvenuto in Nyanime"), 30_000))
        finishWelcome()
        openAdvancedSettings()
        val guide = label("Onboarding guide", "Guida di benvenuto")
        val welcome = label("Welcome to Nyanime", "Benvenuto in Nyanime")
        check(device.waitForFresh(guide, 10_000))
        clickFresh(device, guide)
        check(device.waitForFresh(welcome, 15_000))
        check(device.waitForFresh(label("Your space is ready", "Il tuo spazio è pronto"), 15_000))
        next(label("Make it yours", "Scegli il tuo stile"))
        // Leaving a review must return to Settings, not finish the activity.
        device.pressBack()
        check(device.waitForFresh(welcome, 10_000))
        device.pressBack()
        check(device.waitForFresh(guide, 10_000))
        repeat(2) {
            clickFresh(device, guide)
            check(device.waitForFresh(welcome, 15_000))
            finishWelcome()
            check(device.waitForFresh(guide, 15_000))
            check(!device.hasObject(start))
        }
        // An interrupted manual review starts from the beginning the next time.
        clickFresh(device, guide)
        check(device.waitForFresh(label("Your space is ready", "Il tuo spazio è pronto"), 15_000))
        next(label("Make it yours", "Scegli il tuo stile"))
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/eu.kanade.tachiyomi.ui.main.MainActivity")
        openAdvancedSettings()
        clickFresh(device, guide)
        check(device.waitForFresh(welcome, 15_000))
        device.pressBack()
        check(device.waitForFresh(guide, 10_000))
    }

    @Test fun freshSetupResumesAfterRestartAndDoesNotRepeatAfterCompletion() {
        reset()
        check(device.waitForFresh(label("Welcome to Nyanime", "Benvenuto in Nyanime"), 30_000))
        check(device.waitForFresh(label("Your space is ready", "Il tuo spazio è pronto"), 15_000))
        check(
            device.executeShellCommand("ls -d /sdcard/Android/data/$TARGET_PACKAGE/files/Nyanime").contains("Nyanime"),
        )
        val brand = device.findObject(By.desc("Nyanime")).visibleBounds
        next(label("Make it yours", "Scegli il tuo stile"))
        check(device.findObject(By.desc("Nyanime")).visibleBounds == brand)
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/eu.kanade.tachiyomi.ui.main.MainActivity")
        check(device.waitForFresh(label("Make it yours", "Scegli il tuo stile"), 30_000))
        next(label("You decide what to allow", "Decidi tu cosa consentire"))
        // Denying notifications updates the screen immediately and remains optional.
        clickFresh(device, label("Allow", "Consenti"))
        val deny = By.res(Pattern.compile(".*:id/permission_deny_button"))
        check(device.waitForFresh(deny, 10_000))
        clickFresh(device, deny)
        check(device.waitForFresh(continueButton, 10_000))
        // Optional permissions must not block the footer.
        next(label("Choose your updates", "Scegli gli aggiornamenti"))
        next(finalTitle)
        clickFresh(device, start)
        check(device.waitForFresh(start, 15_000, exists = false))
        check(device.waitForFresh(By.text("Home"), 30_000))
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/eu.kanade.tachiyomi.ui.main.MainActivity")
        check(device.waitForFresh(By.text("Home"), 30_000))
        check(!device.hasObject(label("Welcome to Nyanime", "Benvenuto in Nyanime")))
        check(!device.hasObject(label("Choose your updates", "Scegli gli aggiornamenti")))
        check(!device.hasObject(label("Connect Nyanime Cloud", "Collega Nyanime Cloud")))
    }

    @Test fun invalidLocalBackupCannotStartRestoration() {
        reset("--ez welcome_backup true --ez invalid_backup true")
        check(device.waitForFresh(finalTitle, 30_000))
        clickFresh(device, local)
        val latest = label("LATEST BACKUP", "ULTIMO BACKUP")
        check(device.waitForFresh(latest, 15_000))
        clickFresh(device, latest)
        val invalid = By.text(
            Pattern.compile("This copy did not pass validation.*|Questa copia non supera la verifica.*"),
        )
        check(device.waitForFresh(invalid, 15_000))
        val restoreButton = By.clickable(true).hasDescendant(label("Restore this copy", "Ripristina questa copia"))
        check(!device.findObject(restoreButton).isEnabled)
    }

    @Test fun localRestorationHasAConfirmedTrackedResultBeforeEnteringNyanime() {
        reset("--ez welcome_backup true")
        check(device.waitForFresh(finalTitle, 30_000))
        clickFresh(device, local)
        val latest = label("LATEST BACKUP", "ULTIMO BACKUP")
        check(device.waitForFresh(latest, 15_000))
        clickFresh(device, latest)
        val restore = label("Restore this copy", "Ripristina questa copia")
        check(device.waitForFresh(restore, 15_000))
        clickFresh(device, restore)
        val restored = label("Continue to Nyanime", "Continua su Nyanime")
        check(device.waitForFresh(restored, 45_000))
        clickFresh(device, restored)
        device.pressBack()
        check(device.waitForFresh(label("Your backup has been restored", "Il tuo backup è stato ripristinato"), 15_000))
        clickFresh(device, start)
        check(device.waitForFresh(By.text("Home"), 30_000))
        check(!device.hasObject(By.text(Pattern.compile("Updated to.*|Aggiornato alla.*"))))
    }

    @Test fun automaticStorageReconcilesAChoiceMadeBeforeTheObserverStarts() {
        reset("--ez welcome_storage_race true")
        check(device.waitForFresh(By.text("STORAGE_READY"), 15_000))
    }
}
