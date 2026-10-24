"""Окно программы «Сканы → 1С» на tkinter."""

from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from dataclasses import dataclass, field
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from . import __version__, ai, updater
from .exporter import export_to_folder
from .extract import ExtractedData, extract, parse_amount, parse_date
from .loader import SUPPORTED_EXTENSIONS, configure_tesseract, is_supported, load_document, tesseract_available
from .onec import OneCClient, OneCError, merged_mapping
from .settings import Settings

DOC_TYPES = ["УПД", "Счет-фактура", "Товарная накладная", "Акт", "Счет на оплату", "Платежное поручение"]
VAT_RATES = ["20%", "10%", "0%", "Без НДС"]

ST_NEW, ST_WORK, ST_READY, ST_WARN, ST_SENT, ST_ERROR = (
    "Ожидает", "Распознаётся…", "Распознан", "Проверьте", "Загружен в 1С", "Ошибка")


@dataclass
class DocItem:
    path: Path
    status: str = ST_NEW
    data: ExtractedData | None = None
    text: str = ""
    message: str = ""
    onec_ref: str = ""
    iid: str = field(default="")


def iso_to_ru(value: str) -> str:
    if len(value) == 10 and value[4] == "-":
        return f"{value[8:10]}.{value[5:7]}.{value[0:4]}"
    return value


def fmt_amount(value) -> str:
    if value is None:
        return ""
    return f"{value:,.2f}".replace(",", " ").replace(".", ",")


class App:
    def __init__(self, root: tk.Tk, files: list[str]):
        self.root = root
        self.settings = Settings.load()
        self.items: list[DocItem] = []
        self.events: queue.Queue = queue.Queue()
        self.busy = False
        self.updating = False
        self.current: DocItem | None = None

        root.title(f"Сканы → 1С  {__version__}")
        root.geometry("1200x720")
        root.minsize(900, 560)
        try:
            ttk.Style().theme_use("vista" if os.name == "nt" else "clam")
        except tk.TclError:
            pass

        self._build_toolbar()
        self._build_body()
        self._build_statusbar()
        root.after(100, self._poll_events)

        configure_tesseract()
        if not tesseract_available():
            messagebox.showwarning(
                "Нет OCR",
                "Не найден Tesseract OCR — сканы-изображения распознать не получится.\n"
                "PDF с текстовым слоем читаются и без него.")
        if files:
            self.add_paths([Path(f) for f in files])
            self.recognize()

        updater.cleanup_old()
        if self.settings.auto_update:
            root.after(1500, self.check_updates)

    # ---------- интерфейс ----------

    def _build_toolbar(self):
        bar = ttk.Frame(self.root, padding=(8, 6))
        bar.pack(fill="x")
        buttons = [
            ("Добавить файлы", self.ask_files),
            ("Добавить папку", self.ask_folder),
            ("Распознать", self.recognize),
            ("Загрузить в 1С", self.send),
            ("Удалить из списка", self.remove_selected),
            ("Настройки", self.open_settings),
        ]
        for text, command in buttons:
            ttk.Button(bar, text=text, command=command).pack(side="left", padx=(0, 6))
        ttk.Button(bar, text="Справка", command=self.show_help).pack(side="right")

    def _build_body(self):
        paned = ttk.PanedWindow(self.root, orient="horizontal")
        paned.pack(fill="both", expand=True, padx=8)

        left = ttk.Frame(paned)
        columns = ("file", "status", "type", "number", "date", "seller", "total")
        self.tree = ttk.Treeview(left, columns=columns, show="headings", selectmode="extended")
        headers = {"file": ("Файл", 140), "status": ("Статус", 95), "type": ("Тип", 100),
                   "number": ("Номер", 70), "date": ("Дата", 75), "seller": ("Контрагент", 150),
                   "total": ("Сумма", 85)}
        for col, (title, width) in headers.items():
            self.tree.heading(col, text=title)
            self.tree.column(col, width=width, anchor="e" if col == "total" else "w")
        scroll = ttk.Scrollbar(left, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.tree.tag_configure("error", foreground="#b00020")
        self.tree.tag_configure("warn", foreground="#9a6700")
        self.tree.tag_configure("sent", foreground="#1a7f37")
        self.tree.bind("<<TreeviewSelect>>", self._on_select)
        self.tree.bind("<Double-1>", self._open_scan)
        self.tree.bind("<Delete>", lambda _e: self.remove_selected())
        paned.add(left, weight=3)

        right = ttk.Notebook(paned)
        form = ttk.Frame(right, padding=10)
        right.add(form, text="Реквизиты")
        text_tab = ttk.Frame(right, padding=4)
        right.add(text_tab, text="Распознанный текст")
        paned.add(right, weight=2)

        self.vars: dict[str, tk.StringVar] = {}

        def row(label, key, r, widget="entry", values=None):
            ttk.Label(form, text=label).grid(row=r, column=0, sticky="w", pady=2, padx=(0, 8))
            var = tk.StringVar()
            self.vars[key] = var
            if widget == "combo":
                w = ttk.Combobox(form, textvariable=var, values=values)
            else:
                w = ttk.Entry(form, textvariable=var)
            w.grid(row=r, column=1, sticky="ew", pady=2)

        def section(title, r):
            ttk.Label(form, text=title, font=("Segoe UI", 9, "bold")).grid(
                row=r, column=0, columnspan=2, sticky="w", pady=(10, 2))

        form.columnconfigure(1, weight=1)
        row("Тип документа", "doc_type", 0, "combo", DOC_TYPES)
        row("Номер", "number", 1)
        row("Дата (дд.мм.гггг)", "date", 2)
        section("Продавец / исполнитель", 3)
        row("Наименование", "seller_name", 4)
        row("ИНН", "seller_inn", 5)
        row("КПП", "seller_kpp", 6)
        section("Покупатель (ваша организация)", 7)
        row("Наименование", "buyer_name", 8)
        row("ИНН", "buyer_inn", 9)
        row("КПП", "buyer_kpp", 10)
        section("Суммы и банк", 11)
        row("Сумма всего", "total", 12)
        row("Ставка НДС", "vat_rate", 13, "combo", VAT_RATES)
        row("Сумма НДС", "vat", 14)
        row("БИК", "bik", 15)
        row("Расчётный счёт", "account", 16)

        self.warn_label = ttk.Label(form, text="", foreground="#9a6700", wraplength=380, justify="left")
        self.warn_label.grid(row=17, column=0, columnspan=2, sticky="w", pady=(10, 4))
        ttk.Button(form, text="Сохранить правки", command=self.apply_form).grid(
            row=18, column=0, columnspan=2, sticky="w")

        self.text = tk.Text(text_tab, wrap="word", font=("Consolas", 9))
        tscroll = ttk.Scrollbar(text_tab, orient="vertical", command=self.text.yview)
        self.text.configure(yscrollcommand=tscroll.set)
        self.text.pack(side="left", fill="both", expand=True)
        tscroll.pack(side="right", fill="y")
        ttk.Button(text_tab, text="Извлечь реквизиты из текста заново",
                   command=self.reextract).pack(side="bottom", anchor="w", pady=4)

    def _build_statusbar(self):
        bar = ttk.Frame(self.root, padding=(8, 4))
        bar.pack(fill="x", side="bottom")
        self.status = ttk.Label(bar, text="Добавьте сканы: PDF, JPG, PNG, TIFF, BMP, GIF, WEBP")
        self.status.pack(side="left")
        self.progress = ttk.Progressbar(bar, length=220, mode="determinate")
        self.progress.pack(side="right")

    # ---------- список файлов ----------

    def ask_files(self):
        patterns = " ".join(f"*{ext}" for ext in sorted(SUPPORTED_EXTENSIONS))
        names = filedialog.askopenfilenames(
            title="Выберите сканы",
            filetypes=[("Сканы документов", patterns), ("Все файлы", "*.*")])
        self.add_paths([Path(n) for n in names])

    def ask_folder(self):
        folder = filedialog.askdirectory(title="Папка со сканами")
        if folder:
            self.add_paths(sorted(p for p in Path(folder).rglob("*") if p.is_file() and is_supported(p)))

    def add_paths(self, paths: list[Path]):
        known = {item.path.resolve() for item in self.items}
        added = 0
        for path in paths:
            if path.is_dir():
                self.add_paths(sorted(p for p in path.rglob("*") if p.is_file() and is_supported(p)))
                continue
            if not is_supported(path) or path.resolve() in known:
                continue
            item = DocItem(path=path)
            item.iid = self.tree.insert("", "end", values=(path.name, item.status, "", "", "", "", ""))
            self.items.append(item)
            known.add(path.resolve())
            added += 1
        if added:
            self._set_status(f"Добавлено файлов: {added}. Нажмите «Распознать».")

    def remove_selected(self):
        if self.busy:
            return
        for iid in self.tree.selection():
            self.items = [i for i in self.items if i.iid != iid]
            self.tree.delete(iid)
        self.current = None
        self._fill_form(None)

    def _item(self, iid: str) -> DocItem | None:
        return next((i for i in self.items if i.iid == iid), None)

    def _selected_or_all(self, predicate) -> list[DocItem]:
        selected = [self._item(iid) for iid in self.tree.selection()]
        pool = [i for i in selected if i] or self.items
        return [i for i in pool if predicate(i)]

    def _refresh_row(self, item: DocItem):
        d = item.data
        values = (item.path.name, item.status,
                  d.doc_type if d else "", d.number if d else "", iso_to_ru(d.date) if d else "",
                  (d.seller.name or d.seller.inn) if d else "", fmt_amount(d.total) if d else "")
        tag = {ST_ERROR: "error", ST_WARN: "warn", ST_SENT: "sent"}.get(item.status, "")
        self.tree.item(item.iid, values=values, tags=(tag,) if tag else ())
        if item is self.current:
            self._fill_form(item)

    def _open_scan(self, _event=None):
        item = self.current
        if not item:
            return
        if os.name == "nt":
            os.startfile(item.path)
        else:
            subprocess.Popen(["xdg-open", str(item.path)])

    # ---------- форма ----------

    def _on_select(self, _event=None):
        if self.current:
            self.apply_form(quiet=True)
        selection = self.tree.selection()
        self.current = self._item(selection[0]) if len(selection) == 1 else None
        self._fill_form(self.current)

    def _fill_form(self, item: DocItem | None):
        d = item.data if item else None
        values = {
            "doc_type": d.doc_type if d else "", "number": d.number if d else "",
            "date": iso_to_ru(d.date) if d else "",
            "seller_name": d.seller.name if d else "", "seller_inn": d.seller.inn if d else "",
            "seller_kpp": d.seller.kpp if d else "",
            "buyer_name": d.buyer.name if d else "", "buyer_inn": d.buyer.inn if d else "",
            "buyer_kpp": d.buyer.kpp if d else "",
            "total": fmt_amount(d.total) if d else "", "vat": fmt_amount(d.vat) if d else "",
            "vat_rate": d.vat_rate if d else "", "bik": d.bik if d else "", "account": d.account if d else "",
        }
        for key, value in values.items():
            self.vars[key].set(value)
        lines = []
        if item and item.message:
            lines.append(item.message)
        if d and d.warnings:
            lines += ["⚠ " + w for w in d.warnings]
        self.warn_label.configure(text="\n".join(lines))
        self.text.delete("1.0", "end")
        if item:
            self.text.insert("1.0", item.text)

    def apply_form(self, quiet: bool = False):
        item = self.current
        if not item or not item.data:
            return
        d = item.data
        v = {k: var.get().strip() for k, var in self.vars.items()}
        before = d.to_dict()
        d.doc_type, d.number = v["doc_type"], v["number"]
        d.date = parse_date(v["date"]) if v["date"] else ""
        d.seller.name, d.seller.inn, d.seller.kpp = v["seller_name"], v["seller_inn"], v["seller_kpp"]
        d.buyer.name, d.buyer.inn, d.buyer.kpp = v["buyer_name"], v["buyer_inn"], v["buyer_kpp"]
        d.total = parse_amount(v["total"]) if v["total"] else None
        d.vat = parse_amount(v["vat"]) if v["vat"] else None
        d.vat_rate, d.bik, d.account = v["vat_rate"], v["bik"], v["account"]
        if d.to_dict() != before:
            if item.status in (ST_WARN, ST_ERROR):
                item.status, item.message = ST_READY, "Исправлено вручную"
            self._refresh_row(item)
        if not quiet:
            self._set_status("Правки сохранены")

    def reextract(self):
        item = self.current
        if not item:
            return
        item.text = self.text.get("1.0", "end")
        item.data = extract(item.text)
        item.status = ST_WARN if item.data.warnings else ST_READY
        item.message = ""
        self._refresh_row(item)

    # ---------- фоновая работа ----------

    def _run_background(self, title: str, items: list[DocItem], worker):
        if self.busy:
            messagebox.showinfo("Подождите", "Предыдущая операция ещё выполняется")
            return
        if not items:
            self._set_status("Нечего обрабатывать")
            return
        self.busy = True
        self.progress.configure(maximum=len(items), value=0)
        self._set_status(title)

        def run():
            for index, item in enumerate(items, 1):
                try:
                    worker(item)
                except Exception as exc:  # noqa: BLE001 — показать любую ошибку в строке документа
                    item.status, item.message = ST_ERROR, str(exc)
                self.events.put(("row", item, index))
            self.events.put(("done", title, len(items)))

        threading.Thread(target=run, daemon=True).start()

    def _poll_events(self):
        try:
            while True:
                event = self.events.get_nowait()
                if event[0] == "row":
                    _, item, index = event
                    self._refresh_row(item)
                    self.progress.configure(value=index)
                elif event[0] == "status":
                    self._set_status(event[1])
                elif event[0] == "update_ready":
                    self._on_update_ready(event[1])
                elif event[0] == "update_none":
                    self.updating = False
                    if event[1]:
                        self._set_status(f"У вас последняя версия {__version__}")
                elif event[0] == "update_failed":
                    self.updating = False
                    self._set_status(event[1])
                    if event[2]:
                        messagebox.showwarning("Обновление", event[1])
                elif event[0] == "done":
                    self.busy = False
                    errors = sum(1 for i in self.items if i.status == ST_ERROR)
                    self._set_status(f"Готово. Документов: {event[2]}" + (f", с ошибками: {errors}" if errors else ""))
        except queue.Empty:
            pass
        self.root.after(100, self._poll_events)

    def recognize(self):
        if self.current:
            self.apply_form(quiet=True)
        # Выделенные документы распознаются заново, без выделения — только новые и с ошибками.
        if self.tree.selection():
            items = self._selected_or_all(lambda i: i.status != ST_SENT)
        else:
            items = [i for i in self.items if i.status in (ST_NEW, ST_ERROR)]

        s = self.settings
        client = None
        if s.ai_enabled:
            try:
                client = ai.AIClient(ai.config_from_settings(s))
            except ai.AIError as exc:
                messagebox.showwarning("Распознавание ИИ", f"{exc}. Документы распознаются шаблонами.")

        def work(item: DocItem):
            item.status, item.message = ST_WORK, ""
            self.events.put(("row", item, 0))

            def progress(page, total):
                self.events.put(("status", f"{item.path.name}: страница {page} из {total}"))

            doc = load_document(item.path, progress=progress, keep_images=bool(client and s.ai_send_images))
            item.text = doc.text
            if client:
                self.events.put(("status", f"{item.path.name}: распознаёт ИИ…"))
            item.data, item.message = ai.recognize(client, doc.text, doc.images, extract(doc.text))
            item.status = ST_WARN if item.data.warnings else ST_READY

        self._run_background("Распознавание…", items, work)

    def send(self):
        if self.current:
            self.apply_form(quiet=True)
        items = self._selected_or_all(lambda i: i.data is not None and i.status != ST_SENT)
        if not items:
            self._set_status("Нет распознанных документов для загрузки")
            return
        s = self.settings
        mapping = merged_mapping(s.mapping)

        if s.mode == "folder":
            if not s.exchange_dir:
                messagebox.showwarning("Папка обмена", "Укажите папку обмена в настройках")
                return

            def work(item: DocItem):
                target = export_to_folder(item.data, item.path, Path(s.exchange_dir), item.text)
                item.status, item.message = ST_SENT, f"Выгружено в {target}"
        else:
            try:
                client = OneCClient(s.base_url, s.user, s.password, verify_ssl=s.verify_ssl)
            except OneCError as exc:
                messagebox.showerror("1С", str(exc))
                return

            def work(item: DocItem):
                result = client.upload(item.data, item.path.name, mapping)
                item.onec_ref = result.ref_key
                note = "создан" if result.created else "уже был в базе"
                cp = ", контрагент создан" if result.counterparty_created else ""
                item.status = ST_SENT
                item.message = f"Документ {result.number or result.ref_key} {note}{cp}"

        self._run_background("Загрузка в 1С…", items, work)

    # ---------- автообновление ----------

    def check_updates(self, manual: bool = False):
        """Ищет новую версию на GitHub и сразу её скачивает и ставит — в фоне, не мешая работе."""
        exe = updater.current_exe()
        if self.updating:
            return
        if not exe:
            if manual:
                messagebox.showinfo("Обновление", "Обновляется только собранная программа Scan2-1C.exe")
            return
        self.updating = True

        def run():
            try:
                update = updater.find_update()
                if not update:
                    self.events.put(("update_none", manual))
                    return
                label = f"Скачивается обновление {update.version}"

                def progress(done, total):
                    percent = f" — {done * 100 // total}%" if total else ""
                    self.events.put(("status", f"{label}{percent}"))

                self.events.put(("status", label + "…"))
                updater.install(update, exe, progress=progress)
                self.events.put(("update_ready", update.version))
            except Exception as exc:  # noqa: BLE001 — сбой обновления не должен мешать работе
                self.events.put(("update_failed", str(exc), manual))

        threading.Thread(target=run, daemon=True).start()

    def _on_update_ready(self, version: str):
        self.updating = False
        exe = updater.current_exe()
        # Список пуст — перезапускаемся сразу, иначе спрашиваем, чтобы не потерять распознанное.
        if not self.items and not self.busy:
            self._set_status(f"Установлена версия {version}, перезапуск…")
            self.root.after(800, lambda: self._restart(exe))
            return
        self._set_status(f"Установлена версия {version} — она запустится при следующем открытии программы")
        if messagebox.askyesno(
                "Обновление установлено",
                f"Новая версия {version} скачана и установлена.\n\n"
                "Перезапустить программу сейчас? Несохранённые результаты распознавания пропадут.\n"
                "Если нет — новая версия откроется в следующий раз."):
            self._restart(exe)

    def _restart(self, exe: Path | None):
        if self.busy:
            messagebox.showinfo("Обновление", "Дождитесь окончания текущей операции и перезапустите программу")
            return
        try:
            updater.restart(exe)
        except OSError as exc:
            messagebox.showerror("Обновление", f"Не удалось перезапустить программу: {exc}")
            return
        self.root.destroy()

    # ---------- настройки и справка ----------

    def open_settings(self):
        SettingsDialog(self.root, self.settings, on_check_updates=lambda: self.check_updates(manual=True))

    def show_help(self):
        messagebox.showinfo("Справка", HELP_TEXT)

    def _set_status(self, text: str):
        self.status.configure(text=text)


class SettingsDialog(tk.Toplevel):
    def __init__(self, master, settings: Settings, on_check_updates=None):
        super().__init__(master)
        self.settings = settings
        self.on_check_updates = on_check_updates
        self.title("Настройки")
        self.geometry("760x780")
        self.transient(master)
        self.grab_set()

        frame = ttk.Frame(self, padding=12)
        frame.pack(fill="both", expand=True)
        frame.columnconfigure(1, weight=1)

        self.mode = tk.StringVar(value=settings.mode)
        ttk.Label(frame, text="Куда загружать").grid(row=0, column=0, sticky="w")
        modes = ttk.Frame(frame)
        modes.grid(row=0, column=1, sticky="w")
        ttk.Radiobutton(modes, text="Сразу в базу 1С (OData)", value="odata", variable=self.mode).pack(side="left")
        ttk.Radiobutton(modes, text="В папку обмена (JSON)", value="folder", variable=self.mode).pack(
            side="left", padx=12)

        self.url = tk.StringVar(value=settings.base_url)
        self.user = tk.StringVar(value=settings.user)
        self.password = tk.StringVar(value=settings.password)
        self.verify = tk.BooleanVar(value=settings.verify_ssl)
        self.folder = tk.StringVar(value=settings.exchange_dir)
        self.auto_update = tk.BooleanVar(value=settings.auto_update)
        self.ai_enabled = tk.BooleanVar(value=settings.ai_enabled)
        self.ai_url = tk.StringVar(value=settings.ai_base_url)
        self.ai_key = tk.StringVar(value=settings.ai_api_key)
        self.ai_model = tk.StringVar(value=settings.ai_model)
        self.ai_images = tk.BooleanVar(value=settings.ai_send_images)

        def entry(label, var, r, show=None):
            ttk.Label(frame, text=label).grid(row=r, column=0, sticky="w", pady=3, padx=(0, 8))
            ttk.Entry(frame, textvariable=var, show=show).grid(row=r, column=1, sticky="ew", pady=3)

        entry("Адрес публикации базы", self.url, 1)
        ttk.Label(frame, text="например http://server/accounting — без /odata/standard.odata",
                  foreground="#666").grid(row=2, column=1, sticky="w")
        entry("Пользователь 1С", self.user, 3)
        entry("Пароль", self.password, 4, show="•")
        ttk.Checkbutton(frame, text="Проверять SSL-сертификат", variable=self.verify).grid(
            row=5, column=1, sticky="w")

        ttk.Label(frame, text="Папка обмена").grid(row=6, column=0, sticky="w", pady=3)
        folder_row = ttk.Frame(frame)
        folder_row.grid(row=6, column=1, sticky="ew")
        folder_row.columnconfigure(0, weight=1)
        ttk.Entry(folder_row, textvariable=self.folder).grid(row=0, column=0, sticky="ew")
        ttk.Button(folder_row, text="Обзор…", command=self._browse).grid(row=0, column=1, padx=(6, 0))

        ttk.Label(frame, text="Обновления").grid(row=7, column=0, sticky="w", pady=3)
        update_row = ttk.Frame(frame)
        update_row.grid(row=7, column=1, sticky="w")
        ttk.Checkbutton(update_row, text=f"Обновлять автоматически с GitHub (сейчас {__version__})",
                        variable=self.auto_update).pack(side="left")
        if on_check_updates:
            ttk.Button(update_row, text="Проверить сейчас", command=on_check_updates).pack(side="left", padx=(8, 0))

        ttk.Label(frame, text="Распознавание ИИ", font=("Segoe UI", 9, "bold")).grid(
            row=8, column=0, columnspan=2, sticky="w", pady=(12, 2))
        ai_row = ttk.Frame(frame)
        ai_row.grid(row=9, column=1, sticky="w")
        ttk.Checkbutton(ai_row, text="Распознавать реквизиты ИИ через OmniRoute",
                        variable=self.ai_enabled).pack(side="left")
        ttk.Button(ai_row, text="Проверить OmniRoute", command=self._check_ai).pack(side="left", padx=(8, 0))
        entry("Адрес OmniRoute", self.ai_url, 10)
        entry("Ключ OmniRoute", self.ai_key, 11, show="•")
        entry("Модель", self.ai_model, 12)
        ttk.Checkbutton(frame, text="Отправлять картинки страниц (точнее; нужна модель с vision)",
                        variable=self.ai_images).grid(row=13, column=1, sticky="w")
        ttk.Label(frame, foreground="#666", wraplength=520, justify="left",
                  text="Ключ — в панели OmniRoute: Dashboard → Endpoints. Модель «auto» — OmniRoute выберет сам. "
                       "Сканы уходят тому провайдеру, к которому OmniRoute направит запрос.").grid(
            row=14, column=1, sticky="w")

        ttk.Label(frame, text="Шаблон выгрузки (JSON): какие справочники и документ заполнять").grid(
            row=15, column=0, columnspan=2, sticky="w", pady=(12, 2))
        self.mapping = tk.Text(frame, height=10, font=("Consolas", 9), wrap="none")
        self.mapping.grid(row=16, column=0, columnspan=2, sticky="nsew")
        frame.rowconfigure(16, weight=1)
        self.mapping.insert("1.0", json.dumps(merged_mapping(settings.mapping), ensure_ascii=False, indent=2))

        buttons = ttk.Frame(frame)
        buttons.grid(row=17, column=0, columnspan=2, sticky="e", pady=(10, 0))
        ttk.Button(buttons, text="Шаблон по умолчанию", command=self._reset_mapping).pack(side="left")
        ttk.Button(buttons, text="Проверить подключение", command=self._check).pack(side="left", padx=6)
        ttk.Button(buttons, text="Сохранить", command=self._save).pack(side="left")
        ttk.Button(buttons, text="Отмена", command=self.destroy).pack(side="left", padx=(6, 0))

    def _browse(self):
        folder = filedialog.askdirectory(parent=self, title="Папка обмена с 1С")
        if folder:
            self.folder.set(folder)

    def _reset_mapping(self):
        self.mapping.delete("1.0", "end")
        self.mapping.insert("1.0", json.dumps(merged_mapping({}), ensure_ascii=False, indent=2))

    def _read_mapping(self) -> dict | None:
        try:
            return json.loads(self.mapping.get("1.0", "end"))
        except ValueError as exc:
            messagebox.showerror("Шаблон", f"Ошибка в JSON шаблона: {exc}", parent=self)
            return None

    def _check(self):
        mapping = self._read_mapping()
        if mapping is None:
            return
        try:
            client = OneCClient(self.url.get(), self.user.get(), self.password.get(), timeout=15,
                                verify_ssl=self.verify.get())
            messagebox.showinfo("1С", client.check_connection(merged_mapping(mapping)), parent=self)
        except OneCError as exc:
            messagebox.showerror("1С", str(exc), parent=self)

    def _check_ai(self):
        config = ai.AIConfig(base_url=self.ai_url.get(), api_key=self.ai_key.get().strip(), timeout=15)
        try:
            messagebox.showinfo("OmniRoute", ai.AIClient(config).check(), parent=self)
        except ai.AIError as exc:
            messagebox.showerror("OmniRoute", str(exc), parent=self)

    def _save(self):
        mapping = self._read_mapping()
        if mapping is None:
            return
        s = self.settings
        s.mode, s.base_url, s.user, s.password = self.mode.get(), self.url.get().strip(), self.user.get(), \
            self.password.get()
        s.verify_ssl, s.exchange_dir, s.mapping = self.verify.get(), self.folder.get().strip(), mapping
        s.auto_update = self.auto_update.get()
        s.ai_enabled, s.ai_base_url, s.ai_api_key = self.ai_enabled.get(), self.ai_url.get().strip(), \
            self.ai_key.get().strip()
        s.ai_model, s.ai_send_images = self.ai_model.get().strip() or ai.DEFAULT_MODEL, self.ai_images.get()
        try:
            s.save()
        except OSError as exc:
            messagebox.showerror("Настройки", f"Не удалось сохранить: {exc}", parent=self)
            return
        self.destroy()


HELP_TEXT = """1. Добавьте сканы (PDF, JPG, PNG, TIFF, BMP, GIF, WEBP) кнопками или перетащите файлы на значок программы.
2. «Распознать» — программа прочитает текст и найдёт тип документа, номер, дату, ИНН/КПП сторон, суммы и НДС.
3. Проверьте реквизиты справа (строки «Проверьте» требуют внимания) и при необходимости исправьте.
4. «Загрузить в 1С» — документы создаются в базе непроведёнными, контрагенты ищутся по ИНН и при отсутствии создаются.

Для прямой загрузки база 1С должна быть опубликована на веб-сервере с включённым OData,
а нужные объекты добавлены в состав стандартного интерфейса OData. Подробности — в README."""


def main():
    root = tk.Tk()
    App(root, sys.argv[1:])
    root.mainloop()
