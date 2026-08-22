/**
 * Interface languages.
 *
 * Plural forms go through Intl.PluralRules rather than a hand-rolled rule, so
 * Ukrainian and Polish get their one/few/many distinctions right without this
 * file having to know anything about Slavic grammar.
 */

export const LANGUAGES = {
  en: 'English',
  de: 'Deutsch',
  uk: 'Українська',
  pl: 'Polski',
};

const STRINGS = {
  en: {
    'bar.settings': 'Settings',

    'peers.heading': 'On this network',
    'peers.empty': 'Looking for devices. Start FlyShare on another computer on the same '
      + 'network — it will appear here within seconds.',
    'peers.hint': 'A new device has to be connected first: you compare a six-digit code on '
      + 'both screens, and every transfer after that is encrypted.',
    'peers.connect': 'Connect',
    'peers.count': { one: '{n} device', other: '{n} devices' },

    'send.heading': 'Sending',
    'drop.selectDevice': 'Pick a device to send files to',
    'drop.dropFor': 'Drop files for “{name}”',
    'drop.pickFiles': 'Choose files',
    'drop.pickFolder': 'Choose folder',
    'drop.hint': 'Dragging works too — straight from Explorer or Finder.',

    'transfers.heading': 'Transfers',
    'transfers.openFolder': 'Open downloads folder',
    'transfers.empty': 'Transfers will show up here — both the ones you send and the ones you receive.',

    'status.pending': 'Request',
    'status.connecting': 'Connecting',
    'status.waiting': 'Awaiting consent',
    'status.sending': 'Sending',
    'status.receiving': 'Receiving',
    'status.finalizing': 'Finishing',
    'status.completed': 'Done',
    'status.declined': 'Declined',
    'status.cancelled': 'Cancelled',
    'status.failed': 'Failed',

    'action.accept': 'Accept',
    'action.decline': 'Decline',
    'action.cancel': 'Cancel',
    'action.reveal': 'Show in folder',

    'transfer.to': 'to',
    'transfer.from': 'from',
    'transfer.andMore': '+ {n} more',
    'transfer.of': '{received} of {total}',
    'transfer.remaining': '{time} left',
    'transfer.average': 'avg {rate}',
    'transfer.files': { one: '{n} file', other: '{n} files' },

    'units.bytes': ['B', 'KB', 'MB', 'GB', 'TB'],
    'units.perSecond': '{value}/s',
    'time.seconds': '{s} s',
    'time.minutes': '{m} min {s} s',
    'time.hours': '{h} h {m} min',

    'pair.eyebrow': 'Compare the code',
    'pair.exchanging': 'Exchanging keys…',
    'pair.compareOn': 'Make sure “{name}” is showing the same code.',
    'pair.waitingOn': 'Waiting for confirmation on “{name}”.',
    'pair.match': 'Codes match',
    'pair.noMatch': 'They differ',
    'pair.failed': 'Could not connect',
    'pair.retryHint': 'Try again when both devices are on the network.',
    'pair.close': 'Close',
    'pair.success': 'Connected to “{name}”. Transfers are encrypted from now on.',

    'settings.title': 'Settings',
    'settings.close': 'Close',
    'settings.deviceName': 'Name of this device',
    'settings.deviceNameHint': 'This is how other computers see you.',
    'settings.downloadDir': 'Where incoming files are saved',
    'settings.browse': 'Browse…',
    'settings.language': 'Language',
    'settings.theme': 'Appearance',
    'settings.themeSystem': 'System',
    'settings.themeLight': 'Light',
    'settings.themeDark': 'Dark',
    'settings.view': 'Transfer layout',
    'settings.viewList': 'List',
    'settings.viewGrid': 'Grid',
    'settings.streams': 'Parallel streams',
    'settings.streamsHint': 'More streams keep Wi-Fi busier. 4 works almost everywhere; '
      + 'raise it to 8 if the link is stable and the speed still falls short.',
    'settings.pairedDevices': 'Connected devices',
    'settings.pairedEmpty': 'None yet.',
    'settings.forget': 'Forget',
    'settings.autoAccept': 'Accept files without asking',
    'settings.autoAcceptHint': 'Applies only to devices you already connected.',
    'settings.saved': 'Saved',

    'error.selectDeviceFirst': 'Pick a device to send the files to first.',
    'error.requestFailed': 'Request failed ({status})',
    'error.chunkFailed': 'Part of the file did not go through',
  },

  de: {
    'bar.settings': 'Einstellungen',

    'peers.heading': 'In diesem Netzwerk',
    'peers.empty': 'Suche nach Geräten. Starte FlyShare auf einem anderen Computer im '
      + 'selben Netzwerk — es erscheint hier in wenigen Sekunden.',
    'peers.hint': 'Ein neues Gerät muss zuerst verbunden werden: Du vergleichst einen '
      + 'sechsstelligen Code auf beiden Bildschirmen, danach ist jede Übertragung verschlüsselt.',
    'peers.connect': 'Verbinden',
    'peers.count': { one: '{n} Gerät', other: '{n} Geräte' },

    'send.heading': 'Senden',
    'drop.selectDevice': 'Wähle ein Gerät zum Senden',
    'drop.dropFor': 'Dateien für „{name}“ hier ablegen',
    'drop.pickFiles': 'Dateien wählen',
    'drop.pickFolder': 'Ordner wählen',
    'drop.hint': 'Ziehen funktioniert auch — direkt aus Explorer oder Finder.',

    'transfers.heading': 'Übertragungen',
    'transfers.openFolder': 'Download-Ordner öffnen',
    'transfers.empty': 'Hier erscheinen Übertragungen — gesendete wie empfangene.',

    'status.pending': 'Anfrage',
    'status.connecting': 'Verbinde',
    'status.waiting': 'Warte auf Zustimmung',
    'status.sending': 'Sende',
    'status.receiving': 'Empfange',
    'status.finalizing': 'Schließe ab',
    'status.completed': 'Fertig',
    'status.declined': 'Abgelehnt',
    'status.cancelled': 'Abgebrochen',
    'status.failed': 'Fehlgeschlagen',

    'action.accept': 'Annehmen',
    'action.decline': 'Ablehnen',
    'action.cancel': 'Abbrechen',
    'action.reveal': 'Im Ordner zeigen',

    'transfer.to': 'an',
    'transfer.from': 'von',
    'transfer.andMore': '+ {n} weitere',
    'transfer.of': '{received} von {total}',
    'transfer.remaining': 'noch {time}',
    'transfer.average': 'Ø {rate}',
    'transfer.files': { one: '{n} Datei', other: '{n} Dateien' },

    'units.bytes': ['B', 'KB', 'MB', 'GB', 'TB'],
    'units.perSecond': '{value}/s',
    'time.seconds': '{s} s',
    'time.minutes': '{m} Min {s} s',
    'time.hours': '{h} Std {m} Min',

    'pair.eyebrow': 'Code vergleichen',
    'pair.exchanging': 'Schlüsselaustausch…',
    'pair.compareOn': 'Stelle sicher, dass „{name}“ denselben Code zeigt.',
    'pair.waitingOn': 'Warte auf Bestätigung auf „{name}“.',
    'pair.match': 'Codes stimmen überein',
    'pair.noMatch': 'Sie unterscheiden sich',
    'pair.failed': 'Verbindung fehlgeschlagen',
    'pair.retryHint': 'Versuche es erneut, wenn beide Geräte im Netzwerk sind.',
    'pair.close': 'Schließen',
    'pair.success': 'Mit „{name}“ verbunden. Übertragungen sind ab jetzt verschlüsselt.',

    'settings.title': 'Einstellungen',
    'settings.close': 'Schließen',
    'settings.deviceName': 'Name dieses Geräts',
    'settings.deviceNameHint': 'So sehen dich andere Computer.',
    'settings.downloadDir': 'Wohin eingehende Dateien gespeichert werden',
    'settings.browse': 'Durchsuchen…',
    'settings.language': 'Sprache',
    'settings.theme': 'Erscheinungsbild',
    'settings.themeSystem': 'System',
    'settings.themeLight': 'Hell',
    'settings.themeDark': 'Dunkel',
    'settings.view': 'Darstellung der Übertragungen',
    'settings.viewList': 'Liste',
    'settings.viewGrid': 'Raster',
    'settings.streams': 'Parallele Verbindungen',
    'settings.streamsHint': 'Mehr Verbindungen lasten das WLAN besser aus. 4 passt fast '
      + 'immer; erhöhe auf 8, wenn die Verbindung stabil ist und das Tempo nicht reicht.',
    'settings.pairedDevices': 'Verbundene Geräte',
    'settings.pairedEmpty': 'Noch keine.',
    'settings.forget': 'Entfernen',
    'settings.autoAccept': 'Dateien ohne Nachfrage annehmen',
    'settings.autoAcceptHint': 'Gilt nur für bereits verbundene Geräte.',
    'settings.saved': 'Gespeichert',

    'error.selectDeviceFirst': 'Wähle zuerst ein Gerät aus, an das die Dateien gehen sollen.',
    'error.requestFailed': 'Anfrage fehlgeschlagen ({status})',
    'error.chunkFailed': 'Ein Teil der Datei kam nicht durch',
  },

  uk: {
    'bar.settings': 'Налаштування',

    'peers.heading': 'У цій мережі',
    'peers.empty': 'Шукаю пристрої. Запустіть FlyShare на іншому комп’ютері в цій же '
      + 'мережі — він з’явиться тут за кілька секунд.',
    'peers.hint': 'Новий пристрій треба спершу з’єднати: ви звірите шестизначний код '
      + 'на обох екранах, і після цього всі передачі будуть зашифровані.',
    'peers.connect': 'З’єднати',
    'peers.count': { one: '{n} пристрій', few: '{n} пристрої', many: '{n} пристроїв' },

    'send.heading': 'Надсилання',
    'drop.selectDevice': 'Оберіть пристрій, щоб надіслати файли',
    'drop.dropFor': 'Перетягніть файли для «{name}»',
    'drop.pickFiles': 'Обрати файли',
    'drop.pickFolder': 'Обрати папку',
    'drop.hint': 'Перетягування працює теж — з Провідника або Finder.',

    'transfers.heading': 'Передачі',
    'transfers.openFolder': 'Відкрити папку завантажень',
    'transfers.empty': 'Тут з’являться передачі — і ті, що надсилаєте ви, і вхідні.',

    'status.pending': 'Запит',
    'status.connecting': 'З’єднання',
    'status.waiting': 'Очікую згоди',
    'status.sending': 'Надсилаю',
    'status.receiving': 'Приймаю',
    'status.finalizing': 'Завершую',
    'status.completed': 'Готово',
    'status.declined': 'Відхилено',
    'status.cancelled': 'Скасовано',
    'status.failed': 'Помилка',

    'action.accept': 'Прийняти',
    'action.decline': 'Відхилити',
    'action.cancel': 'Скасувати',
    'action.reveal': 'Показати в папці',

    'transfer.to': 'до',
    'transfer.from': 'від',
    'transfer.andMore': '+ ще {n}',
    'transfer.of': '{received} з {total}',
    'transfer.remaining': 'лишилось {time}',
    'transfer.average': 'середньо {rate}',
    'transfer.files': { one: '{n} файл', few: '{n} файли', many: '{n} файлів' },

    'units.bytes': ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'],
    'units.perSecond': '{value}/с',
    'time.seconds': '{s} с',
    'time.minutes': '{m} хв {s} с',
    'time.hours': '{h} год {m} хв',

    'pair.eyebrow': 'Звірте код',
    'pair.exchanging': 'Обмін ключами…',
    'pair.compareOn': 'Переконайтеся, що на «{name}» показано той самий код.',
    'pair.waitingOn': 'Очікую підтвердження на «{name}».',
    'pair.match': 'Коди збігаються',
    'pair.noMatch': 'Не збігаються',
    'pair.failed': 'Не вдалося з’єднати',
    'pair.retryHint': 'Спробуйте ще раз, коли обидва пристрої будуть у мережі.',
    'pair.close': 'Закрити',
    'pair.success': 'З’єднано з «{name}». Тепер передачі шифруються.',

    'settings.title': 'Налаштування',
    'settings.close': 'Закрити',
    'settings.deviceName': 'Ім’я цього пристрою',
    'settings.deviceNameHint': 'Так вас бачитимуть інші комп’ютери.',
    'settings.downloadDir': 'Куди зберігати вхідні файли',
    'settings.browse': 'Обрати…',
    'settings.language': 'Мова',
    'settings.theme': 'Оформлення',
    'settings.themeSystem': 'Як у системі',
    'settings.themeLight': 'Світла',
    'settings.themeDark': 'Темна',
    'settings.view': 'Вигляд передач',
    'settings.viewList': 'Список',
    'settings.viewGrid': 'Сітка',
    'settings.streams': 'Паралельних потоків',
    'settings.streamsHint': 'Більше потоків краще тримають Wi-Fi завантаженим. 4 підходить '
      + 'майже завжди; підніміть до 8, якщо канал стабільний і швидкість не дотягує.',
    'settings.pairedDevices': 'З’єднані пристрої',
    'settings.pairedEmpty': 'Поки що жодного.',
    'settings.forget': 'Забути',
    'settings.autoAccept': 'Приймати файли без запиту',
    'settings.autoAcceptHint': 'Стосується лише вже з’єднаних пристроїв.',
    'settings.saved': 'Збережено',

    'error.selectDeviceFirst': 'Спершу оберіть пристрій, якому надіслати файли.',
    'error.requestFailed': 'Запит не вдався ({status})',
    'error.chunkFailed': 'Частина файлу не передалася',
  },

  pl: {
    'bar.settings': 'Ustawienia',

    'peers.heading': 'W tej sieci',
    'peers.empty': 'Szukam urządzeń. Uruchom FlyShare na drugim komputerze w tej samej '
      + 'sieci — pojawi się tutaj w kilka sekund.',
    'peers.hint': 'Nowe urządzenie trzeba najpierw połączyć: porównujesz sześciocyfrowy '
      + 'kod na obu ekranach, a potem każdy transfer jest szyfrowany.',
    'peers.connect': 'Połącz',
    'peers.count': { one: '{n} urządzenie', few: '{n} urządzenia', many: '{n} urządzeń' },

    'send.heading': 'Wysyłanie',
    'drop.selectDevice': 'Wybierz urządzenie, aby wysłać pliki',
    'drop.dropFor': 'Przeciągnij pliki dla „{name}”',
    'drop.pickFiles': 'Wybierz pliki',
    'drop.pickFolder': 'Wybierz folder',
    'drop.hint': 'Przeciąganie też działa — prosto z Eksploratora lub Findera.',

    'transfers.heading': 'Transfery',
    'transfers.openFolder': 'Otwórz folder pobierania',
    'transfers.empty': 'Tutaj pojawią się transfery — wysyłane i odbierane.',

    'status.pending': 'Prośba',
    'status.connecting': 'Łączenie',
    'status.waiting': 'Czekam na zgodę',
    'status.sending': 'Wysyłanie',
    'status.receiving': 'Odbieranie',
    'status.finalizing': 'Kończenie',
    'status.completed': 'Gotowe',
    'status.declined': 'Odrzucono',
    'status.cancelled': 'Anulowano',
    'status.failed': 'Błąd',

    'action.accept': 'Przyjmij',
    'action.decline': 'Odrzuć',
    'action.cancel': 'Anuluj',
    'action.reveal': 'Pokaż w folderze',

    'transfer.to': 'do',
    'transfer.from': 'od',
    'transfer.andMore': '+ jeszcze {n}',
    'transfer.of': '{received} z {total}',
    'transfer.remaining': 'pozostało {time}',
    'transfer.average': 'średnio {rate}',
    'transfer.files': { one: '{n} plik', few: '{n} pliki', many: '{n} plików' },

    'units.bytes': ['B', 'KB', 'MB', 'GB', 'TB'],
    'units.perSecond': '{value}/s',
    'time.seconds': '{s} s',
    'time.minutes': '{m} min {s} s',
    'time.hours': '{h} godz {m} min',

    'pair.eyebrow': 'Porównaj kod',
    'pair.exchanging': 'Wymiana kluczy…',
    'pair.compareOn': 'Upewnij się, że „{name}” pokazuje ten sam kod.',
    'pair.waitingOn': 'Czekam na potwierdzenie na „{name}”.',
    'pair.match': 'Kody się zgadzają',
    'pair.noMatch': 'Nie zgadzają się',
    'pair.failed': 'Nie udało się połączyć',
    'pair.retryHint': 'Spróbuj ponownie, gdy oba urządzenia będą w sieci.',
    'pair.close': 'Zamknij',
    'pair.success': 'Połączono z „{name}”. Od teraz transfery są szyfrowane.',

    'settings.title': 'Ustawienia',
    'settings.close': 'Zamknij',
    'settings.deviceName': 'Nazwa tego urządzenia',
    'settings.deviceNameHint': 'Tak zobaczą cię inne komputery.',
    'settings.downloadDir': 'Gdzie zapisywać przychodzące pliki',
    'settings.browse': 'Przeglądaj…',
    'settings.language': 'Język',
    'settings.theme': 'Wygląd',
    'settings.themeSystem': 'Jak w systemie',
    'settings.themeLight': 'Jasny',
    'settings.themeDark': 'Ciemny',
    'settings.view': 'Układ transferów',
    'settings.viewList': 'Lista',
    'settings.viewGrid': 'Siatka',
    'settings.streams': 'Równoległe strumienie',
    'settings.streamsHint': 'Więcej strumieni lepiej obciąża Wi-Fi. 4 sprawdza się prawie '
      + 'zawsze; zwiększ do 8, jeśli łącze jest stabilne, a prędkość nie wystarcza.',
    'settings.pairedDevices': 'Połączone urządzenia',
    'settings.pairedEmpty': 'Jeszcze żadnych.',
    'settings.forget': 'Zapomnij',
    'settings.autoAccept': 'Przyjmuj pliki bez pytania',
    'settings.autoAcceptHint': 'Dotyczy tylko już połączonych urządzeń.',
    'settings.saved': 'Zapisano',

    'error.selectDeviceFirst': 'Najpierw wybierz urządzenie, do którego wysłać pliki.',
    'error.requestFailed': 'Żądanie nie powiodło się ({status})',
    'error.chunkFailed': 'Część pliku nie dotarła',
  },
};

const FALLBACK = 'en';

/** Best available language for a browser preference like "pl-PL" or "uk". */
export function resolveLanguage(preference) {
  if (preference && preference !== 'auto' && STRINGS[preference]) return preference;
  for (const tag of navigator.languages ?? [navigator.language ?? '']) {
    const base = String(tag).toLowerCase().split('-')[0];
    if (STRINGS[base]) return base;
    if (base === 'ua') return 'uk'; // a common mistake for Ukrainian
  }
  return FALLBACK;
}

export function createTranslator(language) {
  const table = STRINGS[language] ?? STRINGS[FALLBACK];
  const fallback = STRINGS[FALLBACK];
  const pluralRules = new Intl.PluralRules(language);

  function lookup(key) {
    return table[key] ?? fallback[key] ?? key;
  }

  function fill(template, vars) {
    if (!vars) return template;
    return template.replace(/\{(\w+)\}/g, (match, name) => (
      Object.hasOwn(vars, name) ? String(vars[name]) : match
    ));
  }

  return {
    language,

    /** Plain lookup with {placeholder} interpolation. */
    t(key, vars) {
      const value = lookup(key);
      return typeof value === 'string' ? fill(value, vars) : key;
    },

    /**
     * Count-aware lookup. Intl picks the form, so "5 файлів" and "5 plików"
     * both come out right without this file encoding either language's rules.
     */
    plural(key, n, vars) {
      const forms = lookup(key);
      if (typeof forms !== 'object') return String(n);
      const form = forms[pluralRules.select(n)] ?? forms.other ?? forms.many ?? forms.one;
      return fill(form, { n, ...vars });
    },

    /** Byte-size units, which differ between Latin and Cyrillic scripts. */
    units() {
      return lookup('units.bytes');
    },
  };
}
