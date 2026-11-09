# SkyLauncher Android 0.1.1-alpha — testbuild

Deze Android-app is nog een **companion voor Minecraft Java-launchers**, geen volledige Minecraft-client.

## Wat werkt
- FCL of Amethyst kiezen.
- Minecraft-versie, modloader en engine onthouden.
- Java-versie adviseren.
- De **officiële Amethyst.apk** van de nieuwste stabiele GitHub Releases van [AngelAuraMC](https://github.com/AngelAuraMC/Amethyst-Android/releases) automatisch downloaden.
- De gedownloade APK controleren (Android-pakket-ID `org.angelauramc.amethyst`) en het normale Android-installatievenster openen.
- Amethyst starten als de app geïnstalleerd is. FCL wordt geopend wanneer geïnstalleerd.
- Modrinth/CurseForge-pagina's openen.

**Beveiliging:** downloaden gebeurt uitsluitend via HTTPS naar GitHub/GitHub-hosted assets. Android controleert of je de app uit deze bron mag installeren en vraagt bevestiging; SkyLauncher kan en mag niet zonder toestemming stil installeren.

## Nog niet ondersteund
- Java wordt NIET door SkyLauncher geïnstalleerd. Gebruik de JRE-manager in Amethyst/FCL.
- SkyLauncher kan Minecraft nog niet zelfstandig starten; de gekozen launcher doet dit.
- Instellingen voor Minecraft-versies of modloaders worden nog niet automatisch doorgegeven aan de gekozen launcher.
- Geen automatische modpack-installatie of Microsoft-aanmelding vanuit SkyLauncher.
- APK is CI-gebouwd, maar nog niet getest op een fysieke Android-telefoon.

## Build
Benodigd: JDK 17, Gradle 8.10.2, Android SDK 35.

```bash
gradle -p android assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Alternatief: download het `SkyLauncher-Android-Prototype-APK` artifact uit de [GitHub Actions Android-build](https://github.com/Newguygame/skylauncher/actions/workflows/android-apk.yml).

Desktop-SkyLauncher 3.1.7 is niet aangepast of verhoogd.
