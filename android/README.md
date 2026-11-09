# SkyLauncher Android 0.3.0-alpha

Dit is de Android-port van SkyLauncher. **De Minecraft Java-game-engine is nog niet
in deze APK geïntegreerd.** Je start Minecraft op dit moment nog via de externe
Android-launcher FCL of Amethyst. Android kan geen willekeurige Windows/Linux Java
Swing JAR rechtstreeks als native APK uitvoeren.

## Desktopfuncties die nu in Android aanwezig zijn

- Origineel SkyLauncher-icoon en vier achtergrondafbeeldingen.
- Homepagina met secties voor profielen, mods, modpacks, instellingen en logs.
- Meerdere profielen aanmaken, selecteren, aanpassen, verwijderen.
- Minecraft-versie, modloader (Vanilla, Fabric, Quilt, Forge, NeoForge)
  en het externe launcherprogramma per profiel bewaren.
- RAM-voorkeur per profiel. **Deze instelling wordt nog niet doorgegeven aan de
  externe Minecraft-engine.**
- Kleurenpresets, aangepaste HEX-accentkleur en wallpaperkeuze.
- Modrinth-mods zoeken per Minecraft-versie en modloader.
- SHA-512-gecontroleerde mods rechtstreeks downloaden en installeren in
  de voor het actieve profiel geselecteerde, beschrijfbare mods-map.
- Reeds geïnstalleerde .jar-bestanden tonen, uitschakelen/inschakelen en verwijderen.
- Modrinth-modpacks zoeken en via officiële website openen (geen import).
- Lokale SkyLauncher-logboeken bekijken en delen.
- Amethyst van officiële GitHub releases downloaden, met expliciete
  Android-bevestiging laten installeren; FCL/Amethyst als externe apps openen.

## Automatische mods-mapdetectie

Bij de eerste start maakt SkyLauncher een **eigen mods-map per profiel** aan.
Via het Storage Access Framework (SAF) kan de gebruiker met de knop
**Minecraft-map toegang geven** een mods-map of bovenliggende map kiezen.
Die toestemming wordt bewaard. SkyLauncher zoekt dan maximaal enkele niveaus
naar een map genaamd `mods`.

Ook eerder verleende maptoegang kan automatisch worden herkend. Als meer dan
één toegestane mods-map wordt gevonden, wordt voor de veiligheid niets naar
een willekeurige andere launcher geschreven: kies zelf de juiste map.
De eigen SkyLauncher-map is **niet automatisch dezelfde** als de mods-map van FCL
of Amethyst. Android 11+ blokkeert directe toegang tot veel mappen onder
`Android/data`; dit wordt **niet** omzeild, ook niet op Android 16/17.

## Nog niet beschikbaar

- Minecraft Java rechtstreeks **in** SkyLauncher starten zonder zichtbare
  Amethyst/FCL-app: vergt integratie van Android JRE/JNI/renderer/authentication.
- Eigen Java-download/installatie, Microsoft-login of Minecraft-installatie.
- Automatische overdracht van SkyLauncher-profielen en RAM naar andere launchers.
- CurseForge-mod downloads, Fathom pack/GitHub pack integratie,
  complete Modrinth/CurseForge modpack-installatie en automatische updates.
- Automatisch oplossen/installeren van vereiste modafhankelijkheden.
- Automatische synchronisatie van spelbestanden met een afgesloten
  externe appmap. Bestanden in app-eigen map moeten nog worden geïmporteerd
  in de externe Minecraft-launcher als die deze opslag niet gebruikt.
- Het delen van dezelfde release-ondertekening als vorige test APK's
  vereist een vaste, veilig opgeslagen signing key; CI gebruikt een tijdelijke
  debugkeystore. Bij installeren over een oudere alpha kan een handmatige
  verwijdering nodig zijn (waardoor lokale instellingen verloren gaan).

## Build

Android SDK 36, Gradle 8.13, JDK 17:

```sh
gradle -p android assembleDebug
```

Bij CI wordt `icons/SkyLauncher.png` plus `assets/background-1.jpg` t/m
`background-4.jpg` als Android-resource meegenomen. Build-output staat
in GitHub Actions-artifact **SkyLauncher-Android-Prototype-APK**.

Desktop SkyLauncher **3.1.7** is niet gewijzigd.

Meer informatie: https://github.com/Newguygame/skylauncher/pull/1
