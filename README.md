# MinecraftD3D – Direct3D-11-Renderer für Minecraft 1.7.10

Forge-Mod (Coremod), der den OpenGL-Renderer von Minecraft 1.7.10 vollständig durch einen nativen Direct3D-11-Renderer
ersetzt. Die Spiellogik bleibt unverändert: Welt, Chunks, Netzwerk, Ressourcen. Das komplette Bild – Welt, GUI, HUD,
Schriften und Menüs – zeichnet eine C++-Bibliothek mit D3D11. Das Fenster wird über GLFW ohne OpenGL-Kontext erstellt.

GL-Aufrufe von Vanilla und Mods werden auf Bytecode-Ebene abgefangen und von einer Emulation der Fixed-Function-Pipeline
von OpenGL auf D3D11 ausgeführt: Matrizen, Zustände, Display Lists, `glBegin`, Vertex-Arrays und VBOs, Texturen, FBOs,
Pixel-Auslesen. Deshalb funktionieren die meisten Mods ohne Änderungen.

*Entwickelt KI-gestützt (Claude Code) unter meiner Leitung – Architektur, Messmethodik und Bewertung der Ergebnisse.*

## Voraussetzungen

- Windows 10/11 x64, Grafikkarte mit Direct3D-11-Unterstützung.
- Minecraft 1.7.10, Forge 10.13.4.1614.
- Java 21 und [lwjgl3ify](https://github.com/GTNewHorizons/lwjgl3ify) (LWJGL3 + RetroFuturaBootstrap).
- [UniMixins](https://github.com/LegacyModdingMC/UniMixins).

## Installation

1. `minecraftd3d-0.1.jar` neben lwjgl3ify und UniMixins in den Ordner `mods/` legen.
2. Zu den JVM-Argumenten des Clients `-Dminecraftd3d=engine` hinzufügen.
3. Das Spiel über `javaw.exe` starten: Manche Treiber begrenzen die Present-Rate für den Prozess `java.exe`.

Ohne `-Dminecraftd3d=engine` läuft das Spiel über den normalen OpenGL-Pfad. Vom Mod bleiben dann nur das erweiterte F3,
die gebündelte Textausgabe und die Render-API aktiv. Derselbe `mods/`-Ordner kann daher für beide Modi verwendet werden.

`minecraftd3d.dll` ist im Jar enthalten und wird beim ersten Start in einen temporären Ordner entpackt. Um einen anderen
Build der DLL zu verwenden, `minecraftd3d.dll` in den Spielordner legen oder den Pfad mit `-Dminecraftd3d.dll=<Pfad>`
angeben.

## Kompatibilität mit Mods

### Inkompatibel (im D3D11-Modus)

| Mod | Grund | Verhalten von MinecraftD3D |
|---|---|---|
| **OptiFine** | Schreibt Welt-Rendering, Tessellator, FontRenderer und TextureMap auf direktes OpenGL um, Quellcode geschlossen | wird automatisch ausgeschlossen |
| **Neodymium** | Eigener GL-Chunk-Renderer (VBO, Shader, `glMultiDrawArrays`) – genau das, was die Engine ersetzt | wird automatisch ausgeschlossen |
| **BetterLoadingScreen** | Zeichnet den Ladebildschirm in einem zweiten GL-Kontext, im D3D11-Modus gibt es keinen OpenGL-Kontext | manuell aus `mods/` entfernen oder zur Ausschlussliste hinzufügen |
| Shaderpacks und Mods mit eigener GL-Pipeline (GLSL/ARB-Shader, Post-Processing) | Die programmierbare OpenGL-Pipeline wird nicht emuliert | der Mod lädt, seine Effekte werden aber nicht gezeichnet, im Log erscheint eine Warnung |

Mods, die Shader nur teilweise nutzen (z. B. GTNHLib, CodeChickenLib, CoFH Core, OpenModsLib), laden und funktionieren,
ihre Shader-Effekte werden im D3D11-Modus jedoch nicht angezeigt.

### OpenGL-Modus (ohne Flag)

Mit OptiFine wendet der Mod keine Änderungen auf `FontRenderer` und `EntityRenderer` an, weil OptiFine diese
umschreibt. Die gebündelte Textausgabe ist dann deaktiviert, alles andere funktioniert wie gewohnt.

### Ausschlussliste

Die betroffenen Jars werden anhand ihres Inhalts erkannt, noch bevor Coremods und FML-Mods geladen werden. Dateien auf
der Festplatte bleiben unverändert. Standardmäßig werden im D3D11-Modus OptiFine und Neodymium ausgeschlossen. Eine
eigene Liste wird über die Datei `config/minecraftd3d-mods-exclude.json` im Spielordner festgelegt. Ist sie vorhanden,
ersetzt sie die eingebauten Regeln vollständig:

```json
{
  "d3d11": [
    { "name": "OptiFine", "match": { "manifestTweakClassPrefix": "optifine.", "anyEntryPrefix": "optifine/" } },
    { "name": "Neodymium", "match": { "mcmodModid": "neodymium", "anyEntryPrefix": "makamys/neodymium/" } },
    { "name": "BetterLoadingScreen", "reason": "zweiter GL-Kontext", "match": { "mcmodModid": "betterloadingscreen" } }
  ],
  "opengl": []
}
```

Die Merkmale `manifestTweakClassPrefix`, `anyEntryPrefix` und `mcmodModid` werden mit ODER verknüpft. Die Bedingungen
`withEntry` und `withoutEntry` (Datei im Jar vorhanden bzw. nicht vorhanden) werden mit UND verknüpft. Welche Jars
ausgeschlossen wurden, steht in `logs/minecraftd3d-mods.log`.

## JVM-Parameter

| Flag | Bedeutung |
|---|---|
| `-Dminecraftd3d=engine` | D3D11-Renderer aktivieren |
| `-Dminecraftd3d.dll=<Pfad>` | angegebene `minecraftd3d.dll` verwenden |
| `-Dminecraftd3d.threads=N` | Anzahl der Threads für das Chunk-Meshing (Standard: Anzahl der Kerne minus 2) |
| `-Dminecraftd3d.flags=N` | Swap-Chain-Modus: 1 – direkt auf dem Fenster, 2 – MaximumFrameLatency 1, 4 – Blt-Modell für Present |
| `-Dminecraftd3d.fbo=false` | keine FBO-Unterstützung melden (Mods nutzen dann den Pfad ohne FBO) |
| `-Dminecraftd3d.gl30=true` | OpenGL 3.0 für Mods melden, die danach fragen |
| `-Dminecraftd3d.frameStats=false` | erweiterte Statistik in F3 deaktivieren |
| `-Dminecraftd3d.f3cache=false` | Zeichnen von F3 nicht cachen |
| `-Dminecraftd3d.serialRender=<Klassen>` | Block-Renderer (kommagetrennt), die in den Meshing-Threads einzeln ausgeführt werden |
| `-Dminecraftd3d.safeBounds=false` | gemeinsame Blockgrenzen für alle Meshing-Threads, wie in Vanilla |
| `-Dminecraftd3d.noWorldFog=true` | ohne Landschaftsnebel |

Der F3-Bildschirm zeigt in beiden Modi Backend und Grafikkarte, FPS, Frame-Zeit (Mittelwert und 1 % low), CPU- und
GPU-Auslastung, Present-Zeit, Anzahl der Draw Calls und die Chunk-Warteschlange.

## Render-API für Mods

Mods können über eine portable API (`com.nikita23830.minecraftd3d.api`) ohne direkte GL-Aufrufe zeichnen. Derselbe Code
läuft unter D3D11 und unter OpenGL. Beschreibung in [docs/API.md](docs/API.md).

## Build

Benötigt werden JDK 21, [llvm-mingw](https://github.com/mstorsjo/llvm-mingw/releases) (ucrt x86_64) und bash
(z. B. Git Bash).

```bash
LLVM_MINGW=/path/to/llvm-mingw-ucrt-x86_64 JAVA_HOME=/path/to/jdk-21 ./native/build.sh
./gradlew build -x test
```

Der erste Befehl baut `native/build/minecraftd3d.dll`, der zweite `build/libs/minecraftd3d-0.1.jar` mit der DLL im Jar
(`natives/minecraftd3d.dll`). Ohne DLL wird das Jar ebenfalls gebaut, funktioniert im D3D11-Modus aber nicht.

## Struktur

| Pfad | Inhalt |
|---|---|
| `src/main/java/com/nikita23830/minecraftd3d/` | Java-Teil: Initialisierung, Chunk-Meshing, JNI, F3 |
| `.../engine/` | OpenGL-Emulation auf D3D11 |
| `.../rfb/` | RetroFuturaBootstrap-Plugin: Fenster ohne GL, Umleitung der GL-Aufrufe, Mod-Filter |
| `.../mixins/`, `.../core/` | Mixins und Coremod |
| `.../api/` | Render-API für Mods |
| `native/` | Quellcode von `minecraftd3d.dll` (C++17, D3D11) und Build-Skript |
| `compile-libs/` | Bibliotheken nur zum Kompilieren (LWJGL3, lwjglx, RFB, lwjgl3ify), werden nicht in den Mod gepackt |

## Kompatibilität mit Grafikkarten

Das Projekt befindet sich im Prototyp-Stadium und läuft nur unter Windows.

| Grafikkarte | Zustand | Bekannte Fehler |
|---|---|---|
| NVIDIA | spielbar | Darstellungsfehler bei HUD und Inventar |
| AMD | spielbar | Darstellungsfehler bei HUD und Inventar, selten fehlerhafter Himmel |
| Intel Arc | viele Fehler | HUD und Inventar, verzerrte Welt, fehlerhafte vorberechnete Chunk-Geometrie, Himmel |

## Messergebnisse

Testsystem: Intel Arc B580, Windows 11, 28 logische Kerne, Fenster 1920×1009, VSync aus. Szene: Vanilla 1.7.10,
2629 nicht leere Chunk-Sektionen, davon ca. 975 sichtbar, ca. 1150 Draw Calls pro Frame. Gemessen nach vollständigem
Laden der Szene und 600 Frames Aufwärmphase: je 1200 Frames mit stehender Kamera und mit 360°-Drehung, Start über
`javaw.exe`.

| Pfad | FPS (stehend / Drehung) | CPU Hauptthread, ms/Frame | Laden der Szene |
|---|---|---|---|
| Vanilla 1.7.10 (OpenGL, Display Lists) | 14 / 12 | 70 / 80 | 634 s |
| Gleiche Pipeline über OpenGL 4.2 | 971 / 1370 | 1,02 / 0,61 | 5,5 s |
| **MinecraftD3D (D3D11)** | **1346 / 1598** | **0,47 / 0,23** | 5,6 s |

Bewertung:

- Der große Abstand zu Vanilla kommt von der **Architektur, nicht vom Grafik-API**: Chunk-Meshing in mehreren Threads,
  eine Sektion = ein Mesh, Frustum Culling. Dieselbe Pipeline über OpenGL erreicht dieselbe Größenordnung.
- D3D11 gegenüber dem optimierten OpenGL-Pfad: **2- bis 3-mal weniger CPU-Zeit im Hauptthread**, der Prozess belegt
  etwa halb so viele Kerne. Die GPU-Zeit für die Welt ist in beiden APIs gleich (Abweichung 1–2 %).
- Gegenüber einem echten Client im OpenGL-Modus mit OptiFine bzw. Neodymium (dort laufen die Chunks ohnehin nicht mehr
  über Display Lists) liegt der Gewinn bei etwa 5 %. Deshalb wird das Projekt derzeit nicht weiterentwickelt.

## Lizenz

MIT – siehe [LICENSE](LICENSE). Die Bibliotheken in `compile-libs/` (LWJGL3, lwjglx, RetroFuturaBootstrap, lwjgl3ify)
werden nur zum Kompilieren benötigt und unterliegen ihren eigenen Lizenzen.
