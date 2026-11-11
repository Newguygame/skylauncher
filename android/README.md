# SkyLauncher Android 0.4.0-alpha — onafhankelijke launcherfuncties

Deze Android-port beheert eigen SkyLauncher-profielen, mods, modpacks zoeken,
achtergronden en instellingen. **Minecraft zelf draait nog via Amethyst of FCL.**
De app gebruikt geen privé Amethyst-bestanden, kopieert geen tokens en kan
Amethyst of een andere Minecraft Java-launcher niet onzichtbaar aansturen zonder
een ondersteunde publieke integratie/interface.

## Nieuwe functies in alpha 0.4.0

- **Eigen Microsoft-aanmelding** in SkyLauncher, via Microsoft Device Code en
  uitsluitend via officiële Microsoft/Xbox/Minecraft-domeinen.
- Na Microsoft OAuth worden Xbox Live / XSTS en de Minecraft Java-profiel-API
  aangeroepen om te controleren of het account een echt Java-profiel heeft.
- Vernieuwingsgegevens worden versleuteld in Android Keystore (AES-GCM)
  opgeslagen; Microsoft-wachtwoorden worden nooit door SkyLauncher ontvangen
  of opgeslagen. De huidige alpha gebruikt het vernieuwingsgegeven nog niet
  voor automatisch verlengen of spelstart.
- Inloggen in SkyLauncher **meldt je niet automatisch aan in Amethyst**.
  Er is geen gedeelde publieke account-API of Android-opslagtoegang voor die
  koppeling. Minecraft-accountoverdracht/SSO wordt daarom niet geclaimd.
- **Controleer SkyLauncher-updates**: zoekt officiële `android-v*` releases
  in GitHub, downloadt de APK over HTTPS, verifieert pakket-ID,
  hoger Android versionCode, SHA-256 wanneer gepubliceerd en **dezelfde
  APK-ondertekeningscertificaten** als de geïnstalleerde app.
  De Android package installer vraagt altijd gebruikersbevestiging.
- In de GitHub Actions-workflow is nu **stabiele ondertekening** mogelijk via
  aparte repository secrets. De sleutel zelf staat niet in GitHub-code.

## Waarom bestaande test-APK's niet konden updaten

GitHub Actions maakte debug-APK's met een tijdelijke signing key. Android
vereist bij updates hetzelfde handtekeningscertificaat. Een build met een
ander certificaat wordt geweigerd. Dit is een beveiligingscontrole van Android,
geen modloaderprobleem.

Om dit te herstellen is een vaste, privé ondertekeningssleutel nodig.
De volgende secrets moeten op GitHub worden gezet onder
`Settings > Secrets and variables > Actions`:

- `SKYLAUNCHER_ANDROID_KEYSTORE_BASE64`: Base64-inhoud van de privé keystore.
- `SKYLAUNCHER_ANDROID_STORE_PASSWORD`: keystore-wachtwoord.
- `SKYLAUNCHER_ANDROID_KEY_ALIAS`: sleutel-alias.
- `SKYLAUNCHER_ANDROID_KEY_PASSWORD`: sleutel-wachtwoord.

**Plaats nooit een private keystore of wachtwoord als repositorybestand.**
Met deze vier secrets bouwt de workflow naast de test-APK ook een
`SkyLauncher-Android-Signed-APK` artifact. Een Git-tag met prefix
`android-v` kan de ondertekende APK publiceren als GitHub Release.
Alleen dan kan de in-app-updatefunctie de APK vinden.

Bestaande v0.3.0-debuginstallaties zullen nog **éénmalig moeten worden
verwijderd** om naar de vaste signeersleutel te wisselen; back-up je
profielen/instellingen voordat je een oude APK verwijdert.
Vanaf de eerste installatie met de vaste sleutel zijn normale upgrades
mogelijk zolang die sleutel niet wordt vervangen.

## Status van overige desktopfuncties

- Profielen: eigen Minecraft-versie, modloader, voorkeurs-RAM en launcherkeuze.
- Mods: Modrinth zoeken, checksumcontrole, eigen/SAF-gedeelde mods-map,
  automatische mapdetectie binnen toegestane opslag, in/uitschakelen, verwijderen.
- Modpacks: zoeken en details openen (geen volledige import of modpack-updates).
- UI: originele desktop-appicon en 4 wallpapers, 5 kleurpresets, eigen kleur.
- Tools & Logs: lokale activiteiten, geen externe gamelogboeken.
- Amethyst-installatie: officiële APK downloaden en Android installeren laten bevestigen.
- Java-runtime en Minecraft spelen: blijven vooralsnog bij FCL of Amethyst.
- Geen Microsoft-tokenoverdracht naar een andere app.
- Geen authentieke desktop-lanceerconsole of geïntegreerde LWJGL/JRE-renderer.

## Build

Android SDK 36, JDK 17, Gradle 8.13.

```bash
gradle -p android assembleDebug
```

Zonder repository secrets is uitsluitend een debug-APK beschikbaar,
waarbij de handtekening per CI-omgeving kan verschillen. Een debug-APK
is **geen** langdurig upgradebare distributieversie. Native Android
Minecraft-start vereist nog een afzonderlijke engine-port.

Desktop SkyLauncher **3.1.7** is ongewijzigd.

Broncode: https://github.com/Newguygame/skylauncher/pull/1
