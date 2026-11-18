# Vitreon Kotlin — pendientes (2026-11-17)

Hecho sin commit: categorías al importar (Keep/Notion/Obsidian/Markdown/respaldo) + 10 funciones de la web (legal, novedades, tips, avatar, fijar y papelera en tarjeta, atajos de teclado). 29 pruebas JVM OK.

- [x] Instalado en TECNO por USB (2026-11-17).
- [x] Botón + y fondo oscuro siguen el color de acento (antes degradado con morado fijo #9333EA).
- [ ] Probar en el teléfono: importar Keep/Notion/Obsidian y revisar categorías, foto de perfil, botones de tarjeta/papelera, atajos con teclado físico.
- [ ] Commit + push.
- [ ] Color de nota de Keep: el modelo `Note` no tiene campo `color` (no se importa).
- [ ] Listas: se importan como Markdown `- [ ]`; no se marca `checklist=true` porque la exportación a la web no convierte de vuelta.
- [ ] Google Drive (requiere OAuth), modal de conflictos de la web (el Kotlin tiene el suyo) — no portados.
- [ ] Textos legales adaptados (app local), no copiados de la web.
- Build: `./gradlew` se atasca descargando Gradle 8.2 → usar `~/.gradle/local/gradle-8.2/bin/gradle --offline testDebugUnitTest assembleDebug`.
