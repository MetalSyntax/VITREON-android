"use strict";
const { Plugin, Notice } = require("obsidian");
module.exports = class SortLines extends Plugin {
  async onload() {
    this.addCommand({ id: "sort-lines", name: "Ordenar líneas", editorCallback: (editor) => {
      const all = !editor.somethingSelected();
      const text = all ? editor.getValue() : editor.getSelection();
      const sorted = text.split("\n").sort((a, b) => a.localeCompare(b)).join("\n");
      if (all) editor.setValue(sorted); else editor.replaceSelection(sorted);
      new Notice("Líneas ordenadas");
    }});
    this.addCommand({ id: "daily", name: "Nota del día", callback: async () => {
      const d = new Date().toISOString().slice(0, 10);
      await this.app.vault.create("Diario/" + d + ".md", "# " + d + "\n\n- [ ] ");
      new Notice("Creada " + d + " · notas: " + this.app.vault.getMarkdownFiles().length);
    }});
  }
};
