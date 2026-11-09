# SkyLauncher Android 0.2.0-alpha — companion / mod manager

**Dit is nog GEEN zelfstandig geïntegreerde Minecraft Java Edition game-engine.**
Om Minecraft zonder Amethyst-app te starten, moet de Amethyst-launcher/game-runtime
binnen SkyLauncher worden geport (JNI, renderer, LWJGL, native libs, Microsoft-login
en JRE). Deze integratie is niet aanwezig in de huidige APK; wees hier duidelijk over.

## Nieuw
- Android API 36 doel, draait op nieuwere Android-versies (Android 17 is nog niet op toestel getest).
- De originele desktop-SkyLauncher-appicon en een van de desktop-achtergronden worden
  bij elke APK-build verpakt; donker startscherm met SkyLauncher-branding.
- Eigen **Modrinth modbrowser in SkyLauncher**: zoeken, filter op Minecraft-versie
  en NeoForge/Fabric/Forge/Quilt, bijpassend .jar downloaden, SHA-512-bestandscontrole.
- Bewaart toegang tot de via Android geselecteerde mods-map, installeert .jar via
  Storage Access Framework. Selecteer de echte mods-map van het gewenste profiel.
- Op moderne Android-versies is toegang tot **Android/data** beperkt. Als de
  geselecteerde launcher alleen daar bestanden bewaart, kan SkyLauncher er niet bij;
  kies dan een ondersteunde gedeelde map of importeer mods handmatig.
- Automatische Amethyst APK-download en normale, door Android bevestigde installatie
  blijft mogelijk. Externe Minecraft-launcher kan als tijdelijke fallback worden geopend.

## Grenzen
- Alleen **mods** (losse .jar-bestanden), geen complete .mrpack-modpacks.
- Vereiste dependencies van mods worden **nog niet automatisch** geïnstalleerd.
- SkyLauncher geeft geen bestaande FCL/Amethyst-profielen of opslaglocaties automatisch
  door, en is nog niet geïntegreerd met hun interne launch-API's.
- Geen Minecraft-direct-launch vanuit het SkyLauncher-proces; hiervoor is een echte
  zelfstandige engine-integratie vereist.
- Minecraft-gebruik vereist eigen geldige licentie en officiële authenticatie.
- De Android-APK is CI-gebouwd, maar nog niet op fysiek toestel getest.

## Build
JDK 17, Gradle 8.13, Android SDK 36.
```bash
gradle -p android assembleDebug
```
GitHub Actions maakt de originele icon/wallpaper vooraf beschikbaar vanuit de
eigen `icons/` en `assets/` van SkyLauncher.
De desktop-versie 3.1.7 is niet gewijzigd.

## Bronnen
- Modrinth API: https://docs.modrinth.com/api/
- Amethyst broncode LGPLv3: https://github.com/AngelAuraMC/Amethyst-Android
