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
    'drop.counting': 'Counting files — {n} so far',

    'transfers.heading': 'Transfers',
    'transfers.openFolder': 'Open downloads folder',
    'transfers.clear': 'Clear the list',
    'transfers.empty': 'Transfers will show up here — both the ones you send and the ones you receive.',

    'status.pending': 'Request',
    'status.scanning': 'Counting files',
    'status.connecting': 'Connecting',
    'status.waiting': 'Awaiting consent',
    'status.sending': 'Sending',
    'status.paused': 'Paused',
    'status.receiving': 'Receiving',
    'status.finalizing': 'Finishing',
    'status.completed': 'Done',
    'status.declined': 'Declined',
    'status.cancelled': 'Cancelled',
    'status.failed': 'Failed',

    'action.accept': 'Accept',
    'action.decline': 'Decline',
    'action.cancel': 'Cancel',
    'action.pause': 'Pause',
    'action.resume': 'Resume',
    'action.reveal': 'Show in folder',
    'action.revealFile': 'Show',
    'action.dismiss': 'Remove',

    'transfer.to': 'to',
    'transfer.from': 'from',
    'transfer.andMore': '+ {n} more',
    'transfer.of': '{received} of {total}',
    'transfer.remaining': '{time} left',
    'transfer.elapsed': '{time} so far',
    'transfer.average': 'avg {rate}',
    'transfer.files': { one: '{n} file', other: '{n} files' },

    'detail.files': 'Files',
    'detail.waiting': 'Waiting',
    'detail.moving': 'Transferring',
    'detail.done': 'Done',
    'detail.failed': 'Incomplete',
    'detail.more': 'Showing the first {shown} of {total}.',
    'detail.noFileList': 'The list of files is not kept once a transfer is over. The folder they landed in is the record.',

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
    'advanced.title': 'Advanced',
    'advanced.open': 'Advanced — direct connection and setup guide',

    'direct.title': 'Direct connection',
    'direct.hint': 'For when the other device never appears. Guest networks, firewalls and '
      + 'some access points drop the announcements devices find each other with, but still '
      + 'carry ordinary traffic. Type the address shown at the top of the other screen and '
      + 'both devices will see each other.',
    'direct.find': 'Find',
    'direct.searching': 'Knocking…',
    'direct.found': 'Found “{name}”. It is in the device list now.',
    'direct.notFound': 'Nothing answered at that address. Check that FlyShare is open on the '
      + 'other device and that both are on the same network.',
    'direct.badAddress': 'That is not an IPv4 address — four numbers separated by dots.',
    'direct.ownAddress': 'That is this device.',

    'guide.title': 'No network?',
    'guide.hint': 'Two devices do not need a router, only the same network. Pick what you have '
      + 'and the steps follow.',
    'guide.thisDevice': 'This device',
    'guide.otherDevice': 'The other device',

    'guide.route.hotspot': 'Phone hotspot',
    'guide.route.cable': 'Cable',
    'guide.route.linux': 'Linux hotspot',
    'guide.route.phone': 'Via a phone',

    'guide.why.hotspot': 'A phone can raise a network anywhere, which is the whole problem '
      + 'solved. Nothing goes over mobile data — traffic between devices on the hotspot never '
      + 'leaves it — so mobile data can stay off.',
    'guide.why.cable': 'By far the fastest option if both machines have an Ethernet port: '
      + 'a gigabit link carries several times what Wi-Fi does, and there is nothing to '
      + 'configure.',
    'guide.why.linux': 'Linux will raise an access point with no internet connection to share, '
      + 'which Windows and macOS generally refuse to do.',
    'guide.why.phone': 'Any phone will do and FlyShare does not need to be on it — it only '
      + 'holds the network up. Windows and macOS both want a connection to share before they '
      + 'will create one, which is exactly what is missing here.',

    'guide.create.android': 'Settings → Connections → Mobile hotspot, and turn it on. Mobile '
      + 'data can stay off: what the two devices send each other never leaves the hotspot.',
    'guide.create.linux': 'Raise an access point with one command. Change the password to '
      + 'something of your own — eight characters or more.',

    'guide.join.windows': 'Click the network icon in the taskbar and join that network.',
    'guide.join.macos': 'Open the Wi-Fi menu in the menu bar and join that network.',
    'guide.join.linux': 'Open the network menu and join that network.',
    'guide.join.android': 'Settings → Wi-Fi, and join that network.',

    'guide.cable.plug': 'Run an Ethernet cable between the two machines. A crossover cable is '
      + 'not needed — network cards have swapped the pairs themselves for twenty years.',
    'guide.cable.wait': 'Configure nothing. With no router handing out addresses, both ends '
      + 'give themselves one after about half a minute.',
    'guide.phone.enable': 'Turn on the hotspot on any phone. FlyShare does not need to be '
      + 'installed on it — the phone only carries the network, and takes no part in the '
      + 'transfer.',

    'guide.open': 'Open FlyShare on both devices. Each appears in the other’s list within a '
      + 'few seconds — then compare the six-digit code once.',
    'guide.fallback': 'If they do not find each other, read the address at the top of one '
      + 'screen and type it into Direct connection above.',


    'error.selectDeviceFirst': 'Pick a device to send the files to first.',
    'error.requestFailed': 'Request failed ({status})',
    'error.chunkFailed': 'Part of the file did not go through',
    'error.stale': 'FlyShare restarted — reloading this page',

    'transfer.slowLink': 'Slower than this Wi-Fi should manage. Both devices on '
      + 'the 5 GHz band and near the router usually fixes it; more parallel '
      + 'streams can help too.',
    'transfer.preparing': 'The other device is making room for the files — '
      + '{done} of {total} ready.',
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
    'drop.counting': 'Zähle Dateien — bisher {n}',
    'drop.pickFolder': 'Ordner wählen',
    'drop.hint': 'Ziehen funktioniert auch — direkt aus Explorer oder Finder.',

    'transfers.heading': 'Übertragungen',
    'transfers.openFolder': 'Download-Ordner öffnen',
    'transfers.clear': 'Liste leeren',
    'transfers.empty': 'Hier erscheinen Übertragungen — gesendete wie empfangene.',

    'status.pending': 'Anfrage',
    'status.scanning': 'Zähle Dateien',
    'status.connecting': 'Verbinde',
    'status.waiting': 'Warte auf Zustimmung',
    'status.sending': 'Sende',
    'status.paused': 'Pausiert',
    'status.receiving': 'Empfange',
    'status.finalizing': 'Schließe ab',
    'status.completed': 'Fertig',
    'status.declined': 'Abgelehnt',
    'status.cancelled': 'Abgebrochen',
    'status.failed': 'Fehlgeschlagen',

    'action.accept': 'Annehmen',
    'action.decline': 'Ablehnen',
    'action.cancel': 'Abbrechen',
    'action.pause': 'Pause',
    'action.resume': 'Fortsetzen',
    'action.reveal': 'Im Ordner zeigen',
    'action.revealFile': 'Zeigen',
    'action.dismiss': 'Entfernen',

    'transfer.to': 'an',
    'transfer.from': 'von',
    'transfer.andMore': '+ {n} weitere',
    'transfer.of': '{received} von {total}',
    'transfer.remaining': 'noch {time}',
    'transfer.elapsed': '{time} bisher',
    'transfer.average': 'Ø {rate}',
    'transfer.files': { one: '{n} Datei', other: '{n} Dateien' },

    'detail.files': 'Dateien',
    'detail.waiting': 'Wartet',
    'detail.moving': 'Wird übertragen',
    'detail.done': 'Fertig',
    'detail.failed': 'Unvollständig',
    'detail.more': 'Die ersten {shown} von {total} werden angezeigt.',
    'detail.noFileList': 'Die Dateiliste wird nach dem Ende einer Übertragung nicht aufbewahrt. Maßgeblich ist der Ordner, in dem die Dateien gelandet sind.',

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
    'advanced.title': 'Erweitert',
    'advanced.open': 'Erweitert — direkte Verbindung und Anleitung',

    'direct.title': 'Direkte Verbindung',
    'direct.hint': 'Für den Fall, dass das andere Gerät gar nicht erscheint. Gästenetze, '
      + 'Firewalls und manche Access Points verwerfen die Ankündigungen, mit denen Geräte '
      + 'einander finden, leiten normalen Verkehr aber weiter. Geben Sie die Adresse ein, die '
      + 'oben auf dem anderen Bildschirm steht — danach sehen sich beide Geräte.',
    'direct.find': 'Suchen',
    'direct.searching': 'Klopfe an…',
    'direct.found': '„{name}“ gefunden. Es steht jetzt in der Geräteleverliste.',
    'direct.notFound': 'Unter dieser Adresse hat nichts geantwortet. Prüfen Sie, ob FlyShare '
      + 'auf dem anderen Gerät läuft und beide im selben Netz sind.',
    'direct.badAddress': 'Das ist keine IPv4-Adresse — vier durch Punkte getrennte Zahlen.',
    'direct.ownAddress': 'Das ist dieses Gerät.',

    'guide.title': 'Kein Netz?',
    'guide.hint': 'Zwei Geräte brauchen keinen Router, nur dasselbe Netz. Wählen Sie, was Sie '
      + 'haben — die Schritte richten sich danach.',
    'guide.thisDevice': 'Dieses Gerät',
    'guide.otherDevice': 'Das andere Gerät',

    'guide.route.hotspot': 'Handy-Hotspot',
    'guide.route.cable': 'Kabel',
    'guide.route.linux': 'Linux-Hotspot',
    'guide.route.phone': 'Über ein Handy',

    'guide.why.hotspot': 'Ein Handy spannt überall ein Netz auf, und damit ist das Problem '
      + 'gelöst. Über die mobilen Daten geht nichts — was die Geräte einander schicken, '
      + 'verlässt den Hotspot nie —, die mobilen Daten können also aus bleiben.',
    'guide.why.cable': 'Mit Abstand am schnellsten, wenn beide Rechner einen Ethernet-Anschluss '
      + 'haben: Gigabit trägt ein Vielfaches von WLAN, und einzustellen ist nichts.',
    'guide.why.linux': 'Linux spannt einen Access Point auch ohne Internetverbindung auf, die '
      + 'sich teilen ließe — Windows und macOS weigern sich dabei meist.',
    'guide.why.phone': 'Jedes Handy genügt, FlyShare muss nicht darauf sein — es hält nur '
      + 'das Netz. Windows und macOS wollen erst eine Verbindung zum Teilen haben, und genau '
      + 'die fehlt hier.',

    'guide.create.android': 'Einstellungen → Verbindungen → Mobiler Hotspot, dann '
      + 'einschalten. Die mobilen Daten können aus bleiben: Was die beiden Geräte einander '
      + 'schicken, verlässt den Hotspot nicht.',
    'guide.create.linux': 'Ein Befehl spannt den Access Point auf. Setzen Sie ein eigenes '
      + 'Passwort ein — acht Zeichen oder mehr.',

    'guide.join.windows': 'Netzwerksymbol in der Taskleiste anklicken und diesem Netz beitreten.',
    'guide.join.macos': 'WLAN-Menü in der Menüleiste öffnen und diesem Netz beitreten.',
    'guide.join.linux': 'Netzwerkmenü öffnen und diesem Netz beitreten.',
    'guide.join.android': 'Einstellungen → WLAN, und diesem Netz beitreten.',

    'guide.cable.plug': 'Ein Ethernet-Kabel zwischen beide Rechner legen. Ein Crossover-Kabel '
      + 'braucht es nicht — Netzwerkkarten drehen die Paare seit zwanzig Jahren selbst.',
    'guide.cable.wait': 'Nichts einstellen. Ohne Router, der Adressen vergibt, geben sich beide '
      + 'Seiten nach etwa einer halben Minute selbst eine.',
    'guide.phone.enable': 'Den Hotspot auf einem beliebigen Handy einschalten. FlyShare muss '
      + 'nicht darauf sein — das Handy trägt nur das Netz und ist an der Übertragung nicht '
      + 'beteiligt.',

    'guide.open': 'FlyShare auf beiden Geräten öffnen. Jedes erscheint binnen Sekunden in der '
      + 'Liste des anderen — dann einmal den sechsstelligen Code vergleichen.',
    'guide.fallback': 'Finden sie sich nicht, lesen Sie die Adresse oben auf einem Bildschirm ab '
      + 'und geben Sie sie oben unter Direkte Verbindung ein.',


    'error.selectDeviceFirst': 'Wähle zuerst ein Gerät aus, an das die Dateien gehen sollen.',
    'error.requestFailed': 'Anfrage fehlgeschlagen ({status})',
    'error.chunkFailed': 'Ein Teil der Datei kam nicht durch',
    'error.stale': 'FlyShare wurde neu gestartet — Seite wird neu geladen',

    'transfer.slowLink': 'Langsamer, als dieses WLAN können sollte. Meist hilft '
      + 'es, beide Geräte ins 5-GHz-Band und näher an den Router zu bringen; '
      + 'mehr parallele Verbindungen können auch helfen.',
    'transfer.preparing': 'Das andere Gerät legt die Dateien an — '
      + '{done} von {total} fertig.',
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
    'drop.counting': 'Рахую файли — поки {n}',
    'drop.pickFolder': 'Обрати папку',
    'drop.hint': 'Перетягування працює теж — з Провідника або Finder.',

    'transfers.heading': 'Передачі',
    'transfers.openFolder': 'Відкрити папку завантажень',
    'transfers.clear': 'Очистити список',
    'transfers.empty': 'Тут з’являться передачі — і ті, що надсилаєте ви, і вхідні.',

    'status.pending': 'Запит',
    'status.scanning': 'Рахую файли',
    'status.connecting': 'З’єднання',
    'status.waiting': 'Очікую згоди',
    'status.sending': 'Надсилаю',
    'status.paused': 'Пауза',
    'status.receiving': 'Приймаю',
    'status.finalizing': 'Завершую',
    'status.completed': 'Готово',
    'status.declined': 'Відхилено',
    'status.cancelled': 'Скасовано',
    'status.failed': 'Помилка',

    'action.accept': 'Прийняти',
    'action.decline': 'Відхилити',
    'action.cancel': 'Скасувати',
    'action.pause': 'Пауза',
    'action.resume': 'Продовжити',
    'action.reveal': 'Показати в папці',
    'action.revealFile': 'Показати',
    'action.dismiss': 'Прибрати',

    'transfer.to': 'до',
    'transfer.from': 'від',
    'transfer.andMore': '+ ще {n}',
    'transfer.of': '{received} з {total}',
    'transfer.remaining': 'лишилось {time}',
    'transfer.elapsed': 'триває {time}',
    'transfer.average': 'середньо {rate}',
    'transfer.files': { one: '{n} файл', few: '{n} файли', many: '{n} файлів' },

    'detail.files': 'Файли',
    'detail.waiting': 'Чекає',
    'detail.moving': 'Передається',
    'detail.done': 'Готово',
    'detail.failed': 'Неповний',
    'detail.more': 'Показано перші {shown} з {total}.',
    'detail.noFileList': 'Список файлів не зберігається після завершення передачі. Записом є папка, у яку вони потрапили.',

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
    'advanced.title': 'Додатково',
    'advanced.open': 'Додатково — пряме підключення та гайд',

    'direct.title': 'Пряме підключення',
    'direct.hint': 'Для випадку, коли інший пристрій так і не з’являється. Гостьові мережі, '
      + 'брандмауери та деякі точки доступу відкидають оголошення, за якими пристрої '
      + 'знаходять одне одного, але звичайний трафік пропускають. Введіть адресу, '
      + 'показану вгорі на іншому екрані — і пристрої побачать одне одного.',
    'direct.find': 'Знайти',
    'direct.searching': 'Стукаю…',
    'direct.found': 'Знайдено «{name}». Він уже в списку пристроїв.',
    'direct.notFound': 'За цією адресою ніхто не відповів. Перевірте, чи відкритий FlyShare '
      + 'на іншому пристрої і чи обидва в одній мережі.',
    'direct.badAddress': 'Це не IPv4-адреса — потрібно чотири числа через крапку.',
    'direct.ownAddress': 'Це адреса цього пристрою.',

    'guide.title': 'Немає мережі?',
    'guide.hint': 'Двом пристроям потрібен не роутер, а спільна мережа. Оберіть, що в вас є — '
      + 'кроки підлаштуються.',
    'guide.thisDevice': 'Цей пристрій',
    'guide.otherDevice': 'Інший пристрій',

    'guide.route.hotspot': 'Точка з телефона',
    'guide.route.cable': 'Кабель',
    'guide.route.linux': 'Точка з Linux',
    'guide.route.phone': 'Через телефон',

    'guide.why.hotspot': 'Телефон створює мережу будь-де — це й є відповідь. Мобільний '
      + 'трафік не витрачається: те, що пристрої передають одне одному, ніколи не '
      + 'покидає точки доступу, тож мобільні дані можна лишити вимкненими.',
    'guide.why.cable': 'Найшвидше, якщо в обох машин є порт Ethernet: гігабіт несе '
      + 'в кілька разів більше за Wi-Fi, і налаштовувати нічого не треба.',
    'guide.why.linux': 'Linux піднімає точку доступу без інтернет-з’єднання, яким '
      + 'ділитися — Windows і macOS зазвичай відмовляються.',
    'guide.why.phone': 'Спрацює будь-який телефон, і FlyShare на ньому не потрібен — '
      + 'він лише тримає мережу. Windows і macOS спочатку хочуть з’єднання, яким '
      + 'ділитися, а саме його тут і немає.',

    'guide.create.android': 'Налаштування → Точка доступу та модем → увімкніть точку '
      + 'доступу Wi-Fi. Мобільні дані можна лишити вимкненими: те, що пристрої '
      + 'передають одне одному, не покидає точки доступу.',
    'guide.create.linux': 'Підніміть точку доступу однією командою. Замініть пароль '
      + 'на свій — вісім символів або більше.',

    'guide.join.windows': 'Натисніть значок мережі на панелі завдань і під’єднайтесь до цієї мережі.',
    'guide.join.macos': 'Відкрийте меню Wi-Fi у рядку меню і під’єднайтесь до цієї мережі.',
    'guide.join.linux': 'Відкрийте меню мережі і під’єднайтесь до цієї мережі.',
    'guide.join.android': 'Налаштування → Wi-Fi і під’єднайтесь до цієї мережі.',

    'guide.cable.plug': 'З’єднайте машини кабелем Ethernet. Кросовер не потрібен — '
      + 'мережеві карти вже двадцять років перевертають пари самі.',
    'guide.cable.wait': 'Нічого не налаштовуйте. Без роутера, який роздає адреси, '
      + 'обидва кінці візьмуть її собі самі за півхвилини.',
    'guide.phone.enable': 'Увімкніть точку доступу на будь-якому телефоні. FlyShare '
      + 'на ньому не потрібен — телефон лише тримає мережу і в передачі '
      + 'участі не бере.',

    'guide.open': 'Відкрийте FlyShare на обох пристроях. Кожен з’явиться в списку '
      + 'іншого за кілька секунд — далі один раз звірте шестизначний код.',
    'guide.fallback': 'Якщо не знайдуть одне одного — подивіться адресу вгорі на '
      + 'одному екрані та введіть її в «Пряме підключення» вище.',


    'error.selectDeviceFirst': 'Спершу оберіть пристрій, якому надіслати файли.',
    'error.requestFailed': 'Запит не вдався ({status})',
    'error.chunkFailed': 'Частина файлу не передалася',
    'error.stale': 'FlyShare перезапустився — оновлюю сторінку',

    'transfer.slowLink': 'Повільніше, ніж має давати цей Wi-Fi. Зазвичай '
      + 'допомагає перевести обидва пристрої на діапазон 5 ГГц і підійти '
      + 'ближче до роутера; іноді дає ефект більше паралельних потоків.',
    'transfer.preparing': 'Інший пристрій готує місце для файлів — '
      + 'готово {done} з {total}.',
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
    'drop.counting': 'Liczę pliki — na razie {n}',
    'drop.pickFolder': 'Wybierz folder',
    'drop.hint': 'Przeciąganie też działa — prosto z Eksploratora lub Findera.',

    'transfers.heading': 'Transfery',
    'transfers.openFolder': 'Otwórz folder pobierania',
    'transfers.clear': 'Wyczyść listę',
    'transfers.empty': 'Tutaj pojawią się transfery — wysyłane i odbierane.',

    'status.pending': 'Prośba',
    'status.scanning': 'Liczę pliki',
    'status.connecting': 'Łączenie',
    'status.waiting': 'Czekam na zgodę',
    'status.sending': 'Wysyłanie',
    'status.paused': 'Wstrzymano',
    'status.receiving': 'Odbieranie',
    'status.finalizing': 'Kończenie',
    'status.completed': 'Gotowe',
    'status.declined': 'Odrzucono',
    'status.cancelled': 'Anulowano',
    'status.failed': 'Błąd',

    'action.accept': 'Przyjmij',
    'action.decline': 'Odrzuć',
    'action.cancel': 'Anuluj',
    'action.pause': 'Pauza',
    'action.resume': 'Wznów',
    'action.reveal': 'Pokaż w folderze',
    'action.revealFile': 'Pokaż',
    'action.dismiss': 'Usuń',

    'transfer.to': 'do',
    'transfer.from': 'od',
    'transfer.andMore': '+ jeszcze {n}',
    'transfer.of': '{received} z {total}',
    'transfer.remaining': 'pozostało {time}',
    'transfer.elapsed': 'trwa {time}',
    'transfer.average': 'średnio {rate}',
    'transfer.files': { one: '{n} plik', few: '{n} pliki', many: '{n} plików' },

    'detail.files': 'Pliki',
    'detail.waiting': 'Czeka',
    'detail.moving': 'Przesyłanie',
    'detail.done': 'Gotowe',
    'detail.failed': 'Niekompletny',
    'detail.more': 'Wyświetlono pierwsze {shown} z {total}.',
    'detail.noFileList': 'Lista plików nie jest przechowywana po zakończeniu transferu. Zapisem jest folder, do którego trafiły.',

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
    'advanced.title': 'Zaawansowane',
    'advanced.open': 'Zaawansowane — połączenie bezpośrednie i przewodnik',

    'direct.title': 'Połączenie bezpośrednie',
    'direct.hint': 'Na wypadek, gdy drugie urządzenie w ogóle się nie pojawia. Sieci dla '
      + 'gości, zapory i niektóre punkty dostępu odrzucają ogłoszenia, po których '
      + 'urządzenia się znajdują, ale zwykły ruch przepuszczają. Wpisz adres widoczny na '
      + 'górze drugiego ekranu — oba urządzenia zobaczą się nawzajem.',
    'direct.find': 'Znajdź',
    'direct.searching': 'Pukam…',
    'direct.found': 'Znaleziono „{name}”. Jest już na liście urządzeń.',
    'direct.notFound': 'Pod tym adresem nikt nie odpowiedział. Sprawdź, czy FlyShare jest '
      + 'otwarty na drugim urządzeniu i czy oba są w tej samej sieci.',
    'direct.badAddress': 'To nie jest adres IPv4 — cztery liczby oddzielone kropkami.',
    'direct.ownAddress': 'To jest to urządzenie.',

    'guide.title': 'Nie ma sieci?',
    'guide.hint': 'Dwa urządzenia nie potrzebują routera, tylko wspólnej sieci. Wybierz, co '
      + 'masz — kroki się dopasują.',
    'guide.thisDevice': 'To urządzenie',
    'guide.otherDevice': 'Drugie urządzenie',

    'guide.route.hotspot': 'Hotspot z telefonu',
    'guide.route.cable': 'Kabel',
    'guide.route.linux': 'Hotspot z Linuksa',
    'guide.route.phone': 'Przez telefon',

    'guide.why.hotspot': 'Telefon zbuduje sieć wszędzie — i to załatwia cały problem. '
      + 'Transfer komórkowy nie jest zużywany: to, co urządzenia wysyłają sobie nawzajem, '
      + 'nigdy nie opuszcza hotspotu, więc dane komórkowe mogą zostać wyłączone.',
    'guide.why.cable': 'Zdecydowanie najszybsze, jeśli oba komputery mają port Ethernet: '
      + 'gigabit przenosi wielokrotność tego co Wi-Fi, a konfigurować nie trzeba nic.',
    'guide.why.linux': 'Linux postawi punkt dostępu bez łącza internetowego do '
      + 'udostępnienia — Windows i macOS zwykle tego odmawiają.',
    'guide.why.phone': 'Wystarczy dowolny telefon i FlyShare nie musi na nim być — telefon '
      + 'tylko trzyma sieć. Windows i macOS najpierw chcą połączenia do udostępnienia, a '
      + 'właśnie jego tu brakuje.',

    'guide.create.android': 'Ustawienia → Połączenia → Hotspot Wi-Fi i włącz go. Dane '
      + 'komórkowe mogą zostać wyłączone: to, co urządzenia sobie przesyłają, nie opuszcza '
      + 'hotspotu.',
    'guide.create.linux': 'Postaw punkt dostępu jednym poleceniem. Zmień hasło na własne — '
      + 'osiem znaków lub więcej.',

    'guide.join.windows': 'Kliknij ikonę sieci na pasku zadań i dołącz do tej sieci.',
    'guide.join.macos': 'Otwórz menu Wi-Fi na pasku menu i dołącz do tej sieci.',
    'guide.join.linux': 'Otwórz menu sieci i dołącz do tej sieci.',
    'guide.join.android': 'Ustawienia → Wi-Fi i dołącz do tej sieci.',

    'guide.cable.plug': 'Połącz komputery kablem Ethernet. Kabel krosowany nie jest '
      + 'potrzebny — karty sieciowe od dwudziestu lat same zamieniają pary.',
    'guide.cable.wait': 'Nie konfiguruj niczego. Bez routera rozdającego adresy obie strony '
      + 'nadadzą je sobie same po około pół minuty.',
    'guide.phone.enable': 'Włącz hotspot na dowolnym telefonie. FlyShare nie musi być na nim '
      + 'zainstalowany — telefon tylko niesie sieć i nie bierze udziału w transferze.',

    'guide.open': 'Otwórz FlyShare na obu urządzeniach. Każde pojawi się na liście drugiego '
      + 'w ciągu kilku sekund — potem raz porównaj sześciocyfrowy kod.',
    'guide.fallback': 'Jeśli się nie znajdą, odczytaj adres na górze jednego ekranu i wpisz '
      + 'go powyżej w Połączeniu bezpośrednim.',


    'error.selectDeviceFirst': 'Najpierw wybierz urządzenie, do którego wysłać pliki.',
    'error.requestFailed': 'Żądanie nie powiodło się ({status})',
    'error.chunkFailed': 'Część pliku nie dotarła',
    'error.stale': 'FlyShare uruchomił się ponownie — przeładowuję stronę',

    'transfer.slowLink': 'Wolniej, niż to Wi-Fi powinno dawać. Zwykle pomaga '
      + 'przeniesienie obu urządzeń na pasmo 5 GHz i zbliżenie ich do routera; '
      + 'czasem pomaga też więcej równoległych strumieni.',
    'transfer.preparing': 'Drugie urządzenie przygotowuje miejsce na pliki — '
      + 'gotowe {done} z {total}.',
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
