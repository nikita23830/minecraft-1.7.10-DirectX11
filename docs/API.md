# MinecraftD3D Render-API für Mods (v0.1)

Mit der Render-API zeichnen Mods **nicht über `GL11.*`, sondern über die Engine**. Die API meldet, welches Backend
aktiv ist (D3D11 oder OpenGL), stellt portable Objekte bereit (Textur, Mesh, Material, Render-Passes, Tile-Renderer)
und bietet einen Zugang zum nativen Gerät des aktiven Backends, falls ein Mod einen eigenen Pfad für eine bestimmte
Grafik-API braucht.

```
Mod ──► com.nikita23830.minecraftd3d.api (portable Schicht) ──► D3D11-Backend (C++, minecraftd3d.dll)   – -Dminecraftd3d=engine
                                                       └──► OpenGL-Backend (Java)                 – normales OpenGL
```

Die API liegt im Jar von MinecraftD3D. Mods kompilieren dagegen mit `compileOnly`.

## Backend abfragen

```java
switch (MinecraftD3DRender.backend()) {
    case D3D11:  // portable API verwenden – oder NativeAccess.d3d11Device() holen und selbst zeichnen
    case OPENGL: // derselbe portable Code funktioniert; ein vorhandener GL-Pfad des Mods ebenfalls
    case NONE:   // Rendering nicht verfügbar (z. B. Server) – nichts registrieren
}
```

Portabler Code (`Mesh`, `Material`, `RenderContext.draw`) wird einmal geschrieben und läuft auf beiden Backends.

## Objekte

| Typ | Beschreibung | Lebensdauer |
|---|---|---|
| `Texture` | eigene Textur (`device().createTexture`) oder Minecraft-Textur (`device().minecraftTexture(ResourceLocation)`, `blockAtlas()`) | bis `close()` |
| `MeshBuilder` → `Mesh` | statische Geometrie: Quads oder Dreiecke, Vertex = Position, UV, Farbe, Normale. Wird einmal auf die GPU geladen (`device().createMesh`) | bis `close()` |
| `Material` | unveränderliche Zustandsbeschreibung: Textur, Blend-Modus (`OPAQUE`, `CUTOUT`, `ALPHA`, `ADDITIVE`), Tiefentest und -schreiben, Backface-Culling, Beleuchtung (Lightmap der Welt + gerichtetes Licht), Nebel | Wert, wird vom Backend gecacht |
| `Matrix4` | einfache 4×4-Matrix (translate/rotate/scale), ohne Abhängigkeit von LWJGL | Wert |

## Wo gezeichnet wird

* `MinecraftD3DRender.registerPass(RenderPass, RenderCallback)` – eigener Code in einem Pass:
  `WORLD_OPAQUE` (nach der undurchsichtigen Welt), `WORLD_TRANSLUCENT` (nach Wasser/Glas), `WORLD_OVERLAY`
  (über der Welt, vor dem GUI), `GUI` (nach dem HUD, in GUI-Bildschirmkoordinaten).
* `MinecraftD3DRender.registerTileRenderer(Class<T extends TileEntity>, TileRenderer<T>)` – Ersatz für TESR: Die Engine
  durchläuft selbst die geladenen Tile Entities dieser Klasse im Umkreis von `MinecraftD3DRender.tileRenderDistance`
  (Standard 64 Blöcke) und ruft den Renderer mit einem `RenderContext` auf.

`RenderContext` liefert im Welt-Pass die Kamera (`cameraX/Y/Z` als double, `partialTicks`) und bietet:

* `draw(Mesh, Material, double x, double y, double z, Matrix4 local, int light, int tint)` – Mesh in Weltkoordinaten.
  Die Umrechnung relativ zur Kamera übernimmt die Engine, daher gibt es keine float-Genauigkeitsprobleme bei großen
  Koordinaten;
* `batch(Material)` → `Batch` – dynamische Geometrie des aktuellen Frames (Strahlen, Partikel, Text in der Welt) in
  Weltkoordinaten;
* `lightAt(x, y, z)` – gepacktes Weltlicht für `draw`;
* `nativeCallback(long fn, long userData)` – nativer Callback im Frame-Strom (siehe unten).

Im Pass `GUI` sind die Koordinaten Bildschirmeinheiten von `ScaledResolution`.

## Beispiel

```java
Mesh cube = MinecraftD3DRender.device().createMesh(new MeshBuilder().begin(MeshBuilder.Mode.QUADS) /* ... */);
Material mat = Material.builder().texture(MinecraftD3DRender.device().blockAtlas()).blend(Material.Blend.CUTOUT).build();

MinecraftD3DRender.registerTileRenderer(TileEntityChest.class, (tile, ctx) ->
    ctx.draw(cube, mat, tile.xCoord + 0.5, tile.yCoord + 1.5, tile.zCoord + 0.5,
             new Matrix4(), ctx.lightAt(tile.xCoord, tile.yCoord + 1, tile.zCoord), 0xFFFFFFFF));
```

## Zugang zum nativen Gerät

`NativeAccess.d3d11Device()` / `NativeAccess.d3d11Context()` liefern die Zeiger `ID3D11Device*` /
`ID3D11DeviceContext*` (0, wenn das Backend nicht D3D11 ist). Im D3D11-Backend wird der Frame erst nach dem Java-Callback
in C++ ausgeführt. Eigener nativer Code wird deshalb über `ctx.nativeCallback(fn, userData)` als Befehl in den
Frame-Strom eingereiht und nicht direkt aus Java aufgerufen. Der Code muss den Zustand der Pipeline danach
wiederherstellen.

## Einschränkungen (v0.1)

* Die API fängt keine fremden `GL11`-Aufrufe ab – das übernimmt die GL-Emulation der Engine.
* Keine eigenen Shader: Es gibt einen festen Satz an Materialien. Für eigene Effekte bleibt der Zugang zum nativen Gerät.
* Keine Skelett-Modelle oder Animationen: Modelle werden als `Mesh` pro Modellteil plus `Matrix4` gezeichnet
  (vergleichbar mit `ModelRenderer`).
* Kein Instancing gleicher Meshes (ein Draw pro Objekt), keine Sortierung halbtransparenter Geometrie zwischen Mods.

## Implementierung

* Java: `src/main/java/com/nikita23830/minecraftd3d/api/` – portable Schicht und beide Backends
  (`impl/D3dApiBackend`, `impl/GlApiBackend`).
* C++: `native/api.inc` – Mesh-Registry für D3D11 und der Befehl „Mesh zeichnen“ im Frame-Strom (dieselbe
  Immediate-Pipeline wie für das GUI, ein Aufruf nach C++ pro Frame).
