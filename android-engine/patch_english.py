#!/usr/bin/env python3
"""English-only UI strings for the integrated SkyLauncher Android variant.

Operates on copied Java source during the bundled-engine CI build.
Does not modify the desktop launcher or the standalone prototype branch.
The native Minecraft engine uses its existing English resources.
"""
from __future__ import annotations
import re
from pathlib import Path

# Translate source Java string literals only. This keeps identifiers, method
# names, preference keys, URLs, and comments unchanged.
REPLACEMENTS = """
SkyLauncher Android gestart||SkyLauncher Android started
SkyLauncher bijwerken||Update SkyLauncher
(alleen SkyLauncher-account)||(SkyLauncher account only)
Actief profiel: ||Active profile: 
 MB voorkeur|| MB preferred
Minecraft Java op Android||Minecraft Java on Android
Beheer profielen, modpacks, mods en instellingen rechtstreeks in SkyLauncher. De game-engine is voorlopig nog extern (FCL of Amethyst).||Manage Minecraft profiles, modpacks, mods and settings directly in SkyLauncher. The game engine is built into this app.
1  Kies je Minecraft-engine||1  Minecraft engine
2  Minecraft-versie||2  Minecraft version
Bijvoorbeeld 1.21.1||For example 1.21.1
3  Modloader||3  Modloader
Open gekozen Minecraft-launcher||Play Minecraft with SkyLauncher Engine
Download / installeer launcher||Advanced engine settings
FCL bevat beheer voor Java 8, 17, 21 en 25 en kan benodigde runtimes beheren. Amethyst heeft een JRE-manager. SkyLauncher heeft nog geen eigen Java-downloader.||The integrated engine manages compatible Android Java runtimes. Additional runtimes may be downloaded on first use.
Hoe installeer ik Java?||How do I install Java?
Java op Android||Java on Android
Open de gekozen launcher en gebruik daar de ingebouwde Java/JRE-manager. Sommige runtimes worden bij de eerste start gedownload. De keuze en installatie gebeuren op dit moment nog niet vanuit SkyLauncher zelf.||The integrated game engine manages the compatible Java runtime. Some versions may need a download before first launch.
Mods en modpacks||Mods and modpacks
Zoek op Modrinth, controleer Minecraft-versie en modloader en installeer de mod rechtstreeks in een zelfgekozen mods-map.||Search Modrinth for mods matching your selected Minecraft version and modloader, then install them in your selected game folder.
Mods zoeken en installeren||Find and install mods
Vind een modpack en importeer het in de gekozen Minecraft-launcher. Directe SkyLauncher-modpackinstallatie volgt later.||Find a compatible modpack. Full .mrpack import inside SkyLauncher is not supported yet.
Bekijk Modrinth-modpacks||Browse Modrinth modpacks
Bekijk CurseForge-modpacks||Browse CurseForge modpacks
Een geldige Minecraft Java-licentie is vereist. Niet verbonden aan Mojang of Microsoft. Alleen officiële downloadpagina's worden geopend.||A valid Minecraft Java Edition license is required. Not affiliated with Mojang or Microsoft.
Aanbevolen: Java ||Recommended: Java 
 (instellen in de gekozen launcher)|| (managed by the integrated engine)
Amethyst automatisch installeren / bijwerken||Manage integrated game engine
FCL-downloadpagina openen||Manage integrated game engine
Launcher gevonden: klaar om te openen. Stel Minecraft en Java in die app in.||The integrated game engine is ready.
Launcher nog niet geïnstalleerd. ||Integrated game engine is ready. 
Gebruik de knop hierboven om Amethyst automatisch te downloaden.||No separate Android launcher is required.
Download FCL via de officiële projectpagina.||No separate Android launcher is required.
Externe game-engine geopend: ||Integrated game engine opened: 
Kon de gekozen launcher niet openen.||Unable to open the integrated game engine.
Minecraft-launcher nog niet gevonden||Minecraft engine unavailable
Je moet eerst ||First install 
 installeren. Android vraagt bij een APK-installatie zelf om toestemming.||. Android must approve APK installations.
Officiële downloadpagina||Official download page
Officiële SkyLauncher-releases controleren...||Checking official SkyLauncher releases...
SkyLauncher Android-update gevonden||SkyLauncher Android update available
 bevat een Android-APK (|| contains an Android APK (
SkyLauncher controleert bij het downloaden de pakketnaam, ||SkyLauncher checks the Android package name, 
de versienummering en de digitale handtekening. ||the version code and digital signature. 
Alleen een met dezelfde sleutel ondertekende APK kan je huidige installatie bijwerken.||Only an APK signed with the same key can update the installed app.
Download en controleer||Download and verify
SkyLauncher-update downloaden...||Downloading SkyLauncher update...
SkyLauncher-update gevalideerd en installatie aangevraagd||SkyLauncher update verified; installation requested
Update mislukt: ||Update failed: 
Update niet geïnstalleerd||Update was not installed
SkyLauncher Android-updates||SkyLauncher Android updates
Op dit moment kunnen testbuilds nog apart via GitHub Actions worden gedownload. ||Experimental builds can be downloaded from GitHub Actions. 
Automatische updates vereisen een officiële Android-release en één vaste privé bewaarde signing key.||Automatic updates require an official release signed with a consistent private signing key.
Amethyst via GitHub installeren||Install old Amethyst app
SkyLauncher downloadt Amethyst.apk rechtstreeks vanaf de officiële GitHub Releases van AngelAuraMC. Daarna opent Android zijn eigen installatievenster en moet je zelf toestemming geven. Er worden geen Minecraft-spelbestanden meegeleverd.||The integrated SkyLauncher Engine does not need a separate Amethyst installation.
Download en installeer||Download and install
Officiële Amethyst-release ophalen...||Downloading official Amethyst release...
Download voltooid. Android-installatie starten...||Download complete. Starting Android installer...
Amethyst APK ontvangen; Android-installatie aangevraagd||Amethyst APK downloaded; Android installation requested
Download mislukt: ||Download failed: 
Amethyst niet geïnstalleerd||Amethyst was not installed
Je kunt Amethyst ook via de officiële releasepagina downloaden.||You can also obtain Amethyst from its official releases.
Officiële releases||Official releases
Installatietoestemming vereist||Installation permission required
Android moet toestaan dat SkyLauncher een APK-installatie start. Schakel 'Toestaan vanaf deze bron' in bij de instellingen. Vervolgens opent automatisch het Android-installatievenster.||Android needs permission to install an APK from SkyLauncher. Enable installation from this source in Android settings.
Open Android-instellingen||Open Android settings
Open handmatig Android > Apps > SkyLauncher > Onbekende apps installeren.||Go to Android > Apps > SkyLauncher > Install unknown apps.
Niet toestaan||Not allowed
Bevestig de SkyLauncher-update in Android; je profielinstellingen blijven behouden als dezelfde signing key wordt gebruikt.||Confirm the update in Android. Your profile settings remain if the signing certificate matches.
Bevestig de installatie in Android. SkyLauncher installeert niets zonder toestemming.||Confirm the Android installation; SkyLauncher never installs an APK without permission.
Kon Android-installatie niet openen: ||Could not open Android installer: 
Android kon de APK niet openen||Android could not open the APK
Het installatievenster kon niet worden geopend.||Unable to open the system installation dialog.
Open releases||Open releases
Geen browser gevonden om de pagina te openen.||No browser was found to open this page.
Engine-instellingen / modloader installeren||Advanced engine settings
Minecraft-game-engine en Java-beheer zijn ingebouwd; ||The Minecraft game engine and Java management are built in; 
de engine downloadt de benodigde runtimes en gamebestanden.||the engine downloads additional runtimes and game files as necessary.
Deze APK bevat de Minecraft-engine zelf. ||The Minecraft engine is included in this APK. 
SkyLauncher gebruikt je profielinstellingen en start Minecraft direct. ||SkyLauncher applies the selected profile and launches Minecraft directly. 
Dit opent geen andere Android-app.||No separate Android app is needed.
Geen aparte Amethyst-app nodig. ||No separate Amethyst app is required. 
Klik op Speel om de geïntegreerde Minecraft-engine te openen. ||Press Play to start the integrated Minecraft engine. 
Het gameprofiel en de Minecraft-login worden in het enginescherm ingesteld.||The game profile and account can be configured in the engine.
SKYLAUNCHER  /  PROFIELEN||SKYLAUNCHER / PROFILES
Maak je eigen Minecraft-profielen en kies Minecraft-versie, modloader en standaard RAM. De externe game-engine heeft nog aparte profielen.||Create Minecraft profiles and choose the Minecraft version, modloader and RAM. SkyLauncher applies these settings to the integrated engine.
Nieuw profiel||New profile
  ✓ actief||  ✓ active
Dit profiel is actief||This is the active profile
Profiel geselecteerd: ||Selected profile: 
Profiel verwijderen||Delete profile
Alleen de SkyLauncher-profielinstellingen worden verwijderd. Minecraft-werelden en mods blijven behouden.||Only SkyLauncher profile settings are removed. Minecraft worlds and mods will be retained.
Minecraft-versie (bijv. 1.21.1)||Minecraft version (e.g. 1.21.1)
Geheugen (MB)||Memory (MB)
Nieuw Minecraft-profiel||New Minecraft profile
Profiel bewerken||Edit profile
Profiel opgeslagen: ||Profile saved: 
SKYLAUNCHER / INSTELLINGEN||SKYLAUNCHER / SETTINGS
Kies een van de desktopstijlen en de originele SkyLauncher-achtergrond.||Choose a desktop theme and an original SkyLauncher background.
Aangepaste accentkleur (HEX, voorbeeld #45DACC)||Custom accent color (HEX, e.g. #45DACC)
Algemene standaard-RAM (MB)||Default RAM allocation (MB)
RAM is voorlopig profielmetadata; de externe Minecraft-engine beheert zijn eigen JVM-geheugen.||Profile RAM is applied automatically to the integrated Minecraft engine.
Instellingen opslaan||Save settings
Ongeldige hexkleur||Invalid HEX color
Stijl opgeslagen||Theme saved
Scherm- en kleuropties zijn van SkyLauncher zelf. Besturingsinstellingen, Java-runtimes en Minecraft-resolutie blijven voorlopig bij de gekozen Minecraft-engine.||SkyLauncher manages its colors, profiles and modloader selection. Advanced controls and renderer settings are managed by the integrated engine.
Logboek delen||Share logs
SkyLauncher-logboek delen||Share SkyLauncher logs
Logboek verversen||Refresh logs
Alleen lokale SkyLauncher-acties worden gelogd. Microsoft-aanmeldingen, gamelogboeken en foutmeldingen van externe launchers zijn niet beschikbaar.||This log shows local SkyLauncher events. Minecraft's separate game log can be found in the integrated engine.
Nog geen SkyLauncher-logboek.||No SkyLauncher logs yet.
Kan log niet openen: ||Could not open log: 
Profiel: ||Profile: 
SkyLauncher detecteert automatisch je eigen profielmap en eerder toegestane Minecraft-mappen. ||SkyLauncher automatically detects your game profile folder and previously authorized Minecraft folders. 
Je kunt ook één keer de Minecraft-map of mods-map selecteren. Android/data blijft op moderne Android-versies afgeschermd.||You can also choose a Minecraft folder or mods folder. Other apps' private Android/data folders remain protected.
Minecraft-map toegang geven / andere map kiezen||Select Minecraft/mods folder
Mods-map automatisch detecteren||Detect mods folder automatically
GEÏNSTALLEERDE MODS||INSTALLED MODS
Zoek mods, bijvoorbeeld Create||Search mods, e.g. Create
Zoek compatibele mods||Find compatible mods
Zoek op naam en installeer een mod.||Search by name and install a mod.
De modzoeker gebruikt de officiële Modrinth API. ||The mod search uses the official Modrinth API. 
Aanvullende vereiste mods (dependencies) worden nog niet automatisch geïnstalleerd. ||Required dependencies are not installed automatically yet. 
Mods in een verkeerde of niet-toegankelijke map zijn niet zichtbaar in Minecraft.||Mods installed in an inaccessible or incorrect directory will not appear in Minecraft.
Mods-map via toegang geselecteerd||Selected mods folder with granted access
Kan de map niet instellen: ||Could not select folder: 
Huidige mods-map: ||Current mods folder: 
Externe toegang bevestigd.||External folder access granted.
Eigen SkyLauncher-opslag (geen automatische FCL/Amethyst-koppeling).||SkyLauncher profile game storage.
Nog geen mods gevonden in de actieve map.||No mods found in the active folder.
Modstatus gewijzigd: ||Mod enabled/disabled: 
Mod wijzigen mislukt: ||Could not change mod: 
Mod verwijderen?||Delete mod?
 permanent uit de geselecteerde mods-map verwijderen?|| permanently from the selected mods folder?
Mod verwijderd: ||Mod deleted: 
Verwijderen mislukt: ||Delete failed: 
Voer minimaal twee tekens in.||Enter at least two characters.
Mods zoeken...||Searching mods...
Zoekfout: ||Search error: 
 passende mods gevonden.|| matching mods found.
Installeer ||Install 
 installeren?||?
SkyLauncher downloadt de nieuwste passende .jar van Modrinth voor ||SkyLauncher downloads the latest compatible JAR from Modrinth for 
 en schrijft hem naar de geselecteerde mods-map. || and saves it to the selected mods folder. 
Vereiste afhankelijkheden worden nog niet automatisch geïnstalleerd.||Required dependencies are not installed automatically.
 downloaden en controleren...|| downloading and verifying...
Mod geïnstalleerd: ||Mod installed: 
 geïnstalleerd in de gedetecteerde mods-map.|| installed in the detected mods folder.
Mod geïnstalleerd||Mod installed
 staat in de gekozen mods-map. || is in the selected mods folder. 
Start Minecraft met dezelfde Minecraft-versie en modloader; ||Launch Minecraft with the same version and modloader; 
installeer eventueel ook vereiste dependencies.||install required dependencies if needed.
Installatie mislukt||Installation failed
Zoek modpacks op Modrinth die overeenkomen met je Minecraft-versie en modloader. ||Search Modrinth for modpacks matching your Minecraft version and modloader. 
Het rechtstreeks importeren van complete .mrpack- of CurseForge-modpacks is nog niet beschikbaar op Android.||Importing full .mrpack or CurseForge packs is not available yet on Android.
Zoek modpack...||Search modpack...
Zoek Modrinth-modpacks||Search Modrinth modpacks
Zoek om compatibele modpacks te vinden.||Search for compatible modpacks.
Modpacks zoeken...||Searching modpacks...
 passende modpacks gevonden.|| matching modpacks found.
Modpackdetails openen||Open modpack details
Modpackzoekfout: ||Modpack search error: 
Appopslag niet beschikbaar||App storage unavailable
/mods (eigen appmap; externe launcher ziet deze niet automatisch)||/mods (private app folder)
/mods (eigen appmap; de Minecraft-engine gebruikt deze map)||/mods (Minecraft engine game folder)
 — meerdere toegestane mods-mappen: kies er één handmatig|| — multiple authorized mods folders: choose one manually
Gevonden: ||Found: 
 in toegestane Android-map|| in an authorized Android folder
Geen mods-map gevonden binnen de gekozen map. Kies de mods-map zelf of een map die een submap 'mods' bevat.||No mods folder found at that location. Select the mods folder itself or a directory containing a mods subfolder.
Ongeldige modbestandsnaam||Invalid mod filename
Deze mod is al geïnstalleerd||This mod is already installed
Kan geen bestand aanmaken in de mods-map||Cannot create a file in the mods folder
Geen schrijftoegang||No write permission
Geen beschrijfbare mods-map||No writable mods directory
Kon mod niet verwijderen||Could not delete mod
Kon mod niet hernoemen||Could not rename mod
Onbetrouwbaar downloadadres||Untrusted download address
Te veel API-data||API response is too large
Mods vereisen een modloader zoals NeoForge, Fabric of Forge.||Mods require a modloader such as NeoForge, Fabric or Forge.
Geen passende modversie gevonden voor Minecraft ||No compatible mod release found for Minecraft 
Mod heeft geen downloadbestand||Mod release has no download file
Geen geschikte .jar gevonden.||No suitable JAR file was found.
Ongeldige modbestandsnaam.||Invalid mod filename.
Het modbestand is te groot of leeg||Mod file is too large or empty
Modbestand overschrijdt maximale grootte||Mod file exceeds the size limit
Onvolledige mod-download||Incomplete mod download
Bestand is afgekeurd: SHA-512-controle mislukt.||File rejected: SHA-512 verification failed.
Meld je aan met je eigen Microsoft-account om het Minecraft Java-profiel te controleren. ||Sign in with your own Microsoft account to verify your Minecraft Java Edition profile. 
SkyLauncher vraagt nooit om je Microsoft-wachtwoord. ||SkyLauncher never asks for your Microsoft password. 
Aanmelden en spelen via Amethyst blijven voorlopig gescheiden.||The integrated engine currently has a separate one-time game-account sign-in.
Huidige account: ||Current account: 
Afmelden bij SkyLauncher||Sign out of SkyLauncher
Afmelden?||Sign out?
De lokaal opgeslagen SkyLauncher-accountgegevens worden verwijderd. Dit meldt je niet af bij Amethyst of Microsoft in je browser.||Locally saved SkyLauncher credentials will be removed. This does not sign you out of the integrated Minecraft engine or Microsoft in your browser.
SkyLauncher-account afgemeld||Signed out of SkyLauncher
Inloggen met Microsoft||Sign in with Microsoft
Andere Microsoft-account||Switch Microsoft account
Microsoft device-code-aanmelding: veilig via de officiële Microsoft-website.||Secure Microsoft device-code sign-in via Microsoft's official website.
Kopieer Microsoft-code||Copy Microsoft code
Code gekopieerd||Code copied
Open Microsoft-aanmeldwebsite||Open Microsoft sign-in page
Aanmelding annuleren||Cancel sign-in
Aanmelding geannuleerd.||Sign-in cancelled.
Een Minecraft-licentie is vereist. Sommige Microsoft-appregistraties ||A Minecraft license is required. Some Microsoft app registrations 
hebben nog extra toestemming nodig om Minecraft Services te gebruiken. ||require additional permissions to access Minecraft Services. 
Accounttokens worden versleuteld bewaard op dit apparaat en niet gedeeld met Amethyst.||Account tokens are stored encrypted on this device and are not automatically transferred to the embedded game engine.
Microsoft-aanmeldcode aanvragen...||Requesting Microsoft sign-in code...
Open de Microsoft-website en voer de code in. ||Open the Microsoft website and enter the code. 
SkyLauncher controleert daarna automatisch je Minecraft-profiel.||SkyLauncher will then verify your Minecraft profile.
Minecraft-profiel geverifieerd||Minecraft profile verified
Minecraft-profiel gevonden||Minecraft profile found
Aangemeld als ||Signed in as 
. Je account is beschikbaar in SkyLauncher, maar Amethyst gebruikt voorlopig zijn eigen aanmeldsysteem.||. Your SkyLauncher account is ready. The integrated game engine currently uses a separate sign-in.
Microsoft-aanmelding mislukt (zonder tokenopslag)||Microsoft sign-in failed (no token saved)
Microsoft gaf een onbekende aanmeldwebsite terug.||Microsoft returned an unrecognized sign-in page.
Microsoft-aanmelding: ||Microsoft sign-in: 
Microsoft verbonden. Minecraft-licentie controleren...||Microsoft connected. Checking Minecraft ownership...
Geen geldig Minecraft Java-profiel aangetroffen.||No valid Minecraft Java Edition profile found.
De Microsoft-aanmeldcode is verlopen.||The Microsoft device code expired.
Onbekende aanmeldfout||Unknown sign-in error
Onveilig authenticatie-adres.||Unsafe authentication URL.
Authserver gaf geen antwoord (||Authentication server did not respond (
Authserver-antwoord te groot.||Authentication response too large.
Minecraft Services weigerde de app (403). ||Minecraft Services rejected the app (403). 
Controleer de Microsoft/Minecraft-toestemming van de SkyLauncher-appregistratie.||Check the Microsoft/Minecraft permissions of the SkyLauncher app registration.
Dit account heeft geen geldig Minecraft Java-profiel.||This account has no valid Minecraft Java Edition profile.
Microsoft/Minecraft antwoord: ||Microsoft/Minecraft response: 
Er is nog geen Android-APK gepubliceerd in de nieuwste officiële GitHub-release.||No Android APK has been published in the latest official GitHub release.
De publicatie heeft geen geldige APK-grootte.||The published APK has an invalid size.
Onbekende netwerkfout.||Unknown network error.
De update is niet van GitHub afkomstig.||The update is not hosted on GitHub.
Er is geen downloadmap beschikbaar.||No download directory is available.
Tijdelijke update kon niet worden verwijderd.||Could not delete temporary update file.
Downloadserver gaf HTTP ||Download server returned HTTP 
Onbekende downloadserver voor update.||Unrecognized update download server.
De update is groter dan toegestaan.||The update exceeds the allowed size.
SkyLauncher downloaden: ||Downloading SkyLauncher: 
De APK is onvolledig gedownload.||The APK download was incomplete.
SHA-256-controle mislukt: update geweigerd.||SHA-256 verification failed: update rejected.
Vorige updatebestand kon niet vervangen worden.||Could not replace the previous update file.
APK kon niet worden klaargezet.||Could not prepare APK for installation.
Download mislukt||Download failed
Download is geen SkyLauncher Android APK.||Downloaded file is not a SkyLauncher Android APK.
Deze versie is niet nieuwer dan de geïnstalleerde SkyLauncher.||This version is not newer than the installed SkyLauncher.
Update is anders ondertekend. Android accepteert geen update ||Update has a different signing certificate. Android will not accept an update 
met een andere handtekening. Er is één vaste signing key nodig.||signed with another certificate. One consistent signing key is required.
Geen vertrouwde update-API.||No trusted update API.
Release-informatie is te groot.||Release information is too large.
App-opslag voor downloads is niet beschikbaar.||App storage is unavailable for downloads.
Oude tijdelijke download kon niet worden verwijderd.||Could not delete the old temporary download.
GitHub gaf HTTP ||GitHub returned HTTP 
. Probeer later opnieuw.||. Try again later.
De APK komt niet van een vertrouwde GitHub-downloadserver.||The APK is not from a trusted GitHub download server.
APK is onverwacht groot.||The APK is unexpectedly large.
Amethyst wordt gedownload...||Downloading Amethyst...
Download is te groot.||Download is too large.
Amethyst downloaden: ||Downloading Amethyst: 
Download is te klein om een geldige APK te zijn.||Download is too small to be a valid APK.
Het gedownloade bestand is geen geldige Android APK.||The downloaded file is not a valid Android APK.
Het bestand is geen officiële Amethyst-app (pakket-ID klopt niet).||The file is not an official Amethyst app (package ID mismatch).
Oude download kon niet vervangen worden.||Could not replace the previous download.
Kon de APK niet klaarzetten voor installatie.||Could not prepare APK for installation.
""".strip()

PAIRS = []
for line in REPLACEMENTS.splitlines():
    if not line.strip() or "||" not in line:
        continue
    original, english = line.split("||", 1)
    PAIRS.append((original, english))
PAIRS.sort(key=lambda p: len(p[0]), reverse=True)

LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')


def patch_skylauncher_java(src_dir: Path) -> None:
    count = 0
    for path in src_dir.glob("*.java"):
        raw = path.read_text(encoding="utf-8")
        def apply(match):
            nonlocal count
            literal = match.group(0)[1:-1]
            updated = literal
            for old, new in PAIRS:
                if old in updated:
                    updated = updated.replace(old, new)
                    count += 1
            return '"' + updated + '"'
        translated = LITERAL.sub(apply, raw)
        if translated != raw:
            path.write_text(translated, encoding="utf-8")
    print(f"Translated {count} SkyLauncher Android string occurrences into English.")


def force_native_english(engine: Path) -> None:
    # The upstream app provides translations based on Android's system locale.
    # SkyLauncher currently offers an English-only UI. This forces the upstream
    # engine's dialogs and settings to match.
    pkg = engine / "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch"
    locale = pkg / "utils/LocaleUtils.java"
    original = locale.read_text(encoding="utf-8")
    if "if(PREF_FORCE_ENGLISH){" not in original:
        raise RuntimeError("Cannot locate native engine locale selector")
    patched = original.replace("if(PREF_FORCE_ENGLISH){",
        "PREF_FORCE_ENGLISH = true;\n        if(PREF_FORCE_ENGLISH){", 1)
    locale.write_text(patched, encoding="utf-8")
    prefs = pkg / "prefs/LauncherPreferences.java"
    original = prefs.read_text(encoding="utf-8")
    line = 'PREF_FORCE_ENGLISH = DEFAULT_PREF.getBoolean("force_english", false);'
    if line not in original:
        raise RuntimeError("Cannot find engine force_english preference")
    prefs.write_text(original.replace(line, "PREF_FORCE_ENGLISH = true;", 1),
                     encoding="utf-8")
