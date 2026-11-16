# Vitreon (Android, Kotlin/Compose)

Versión nativa de **Vitreon Notes** (la app web React en `~/Documents/GitHub/VITREON`), con el mismo estilo de vidrio índigo,
todas sus funciones y los errores de la web corregidos (ver `docs/VITREON_WEB_SPEC.md`).

## Qué trae
- Notas en Markdown con modo lectura/edición, barra de formato, categorías (16 colores, 70 íconos), etiquetas, fijadas,
  archivo, papelera (30 días), orden manual arrastrando, búsqueda por texto, `#etiqueta` y voz, cuadrícula/lista/tarjeta.
- Sintaxis de Obsidian: `[[enlaces]]` con alias, backlinks, `#etiquetas`, tareas `- [ ]`, callouts `> [!tip]`, `==resaltado==`,
  tablas, bloques de código y frontmatter.
- Adjuntos cifrados: fotos, cámara, notas de voz y dibujos.
- Seguridad: archivo completo cifrado (AES-256-GCM, clave del Android Keystore), PIN con hash PBKDF2 y bloqueo por intentos,
  huella, notas bloqueadas cifradas con la clave del PIN.
- Importar: Google Keep (zip de Takeout), Notion (zip Markdown & CSV), bóveda de Obsidian (carpeta o zip), Vitreon web (JSON),
  `.vitreon` propio (con contraseña) y `.md` sueltos; conflictos con mantener / reemplazar / ambas.
- Exportar: `.vitreon` cifrado con contraseña, JSON compatible con la web (con categorías) y Markdown `.zip`.
- Plugins de Obsidian (beta): corren en un WebView aislado sin red, con un módulo `obsidian` compatible con `Plugin`,
  `addCommand`/`editorCallback`, `app.vault`, `Notice` y `loadData/saveData`. Sus comandos salen en la paleta del editor.
  Los que dibujan vistas o paneles de escritorio cargan pero no muestran nada.

## Compilar y probar
```
~/.gradle/local/gradle-8.2/bin/gradle testReleaseUnitTest assembleRelease   # APK en app/build/outputs/apk/release/
node tools/plugin-test/run.js                                              # host de plugins con el plugin de ejemplo
```
